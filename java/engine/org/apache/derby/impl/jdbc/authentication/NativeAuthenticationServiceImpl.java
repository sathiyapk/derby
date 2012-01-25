/*

   Derby - Class org.apache.derby.impl.jdbc.authentication.NativeAuthenticationServiceImpl

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.jdbc.authentication;

import java.util.Properties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;

import org.apache.derby.catalog.SystemProcedures;
import org.apache.derby.iapi.db.Database;
import org.apache.derby.iapi.reference.Property;
import org.apache.derby.iapi.services.info.JVMInfo;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.PasswordHasher;
import org.apache.derby.iapi.sql.dictionary.UserDescriptor;
import org.apache.derby.iapi.reference.Attribute;
import org.apache.derby.iapi.reference.SQLState;
import org.apache.derby.authentication.UserAuthenticator;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.services.property.PropertyUtil;
import org.apache.derby.iapi.services.sanity.SanityManager;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.util.IdUtil;
import org.apache.derby.iapi.util.StringUtil;
import org.apache.derby.impl.jdbc.Util;

/**
 * <p>
 * This authentication service supports Derby NATIVE authentication.
 * </p>
 *
 * <p>
 * To activate this service, set the derby.authentication.provider database
 * or system property to a value beginning with the token "NATIVE:".
 * </p>
 *
 * <p>
 * This service instantiates and calls the basic User authentication scheme at runtime.
 * </p>
 *
 * <p>
 * User credentials are defined in the SYSUSERS table.
 * </p>
 *
 */
public final class NativeAuthenticationServiceImpl
	extends AuthenticationServiceBase implements UserAuthenticator
{
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // CONSTANTS
    //
    ///////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // STATE
    //
    ///////////////////////////////////////////////////////////////////////////////////

    // temporary, used when bootstrapping a locally authenticated database
    private boolean _creatingCredentialsDB = false;
    
    private String      _credentialsDB;
    private boolean _authenticateDatabaseOperationsLocally;

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // ModuleControl BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

	/**
	 *  Check if we should activate this authentication service.
	 */
	public boolean canSupport(Properties properties)
    {
		if (!requireAuthentication(properties)) { return false; }

        if ( PropertyUtil.nativeAuthenticationEnabled( properties ) )
        {
            parseNativeSpecification( properties );

            return true;
        }
        else { return false; }
	}

    /**
     * <p>
     * Parse the specification of NATIVE authentication. It can take 3 forms:
     * </p>
     *
     * <ul>
     * <li><i>NATIVE:$credentialsDB</i> - Here $credentialsDB is the name of a Derby database.
     *  This means that all authentication should take place in $credentialsDB.</li>
     * <li><i>NATIVE:$credentialsDB:LOCAL</i>- This means that system-wide operations (like engine shutdown)
     *  are authenticated in $credentialsDB but connections to existing databases are authenticated
     *  in those databases.</li>
     * <li><i>NATIVE::LOCAL</i> - This means that connections to a given database are authenticated
     *  in that database.</li>
     * </ul>
     */
    private void    parseNativeSpecification( Properties properties )
    {
        // If we get here, we already know that the authentication provider property
        // begins with the NATIVE: token
        String authenticationProvider = PropertyUtil.getPropertyFromSet
            (
             properties,
             Property.AUTHENTICATION_PROVIDER_PARAMETER
             );

        _authenticateDatabaseOperationsLocally = PropertyUtil.localNativeAuthenticationEnabled( properties );

        // Everything between the first colon and the last colon is the name of a database
        int     dbNameStartIdx = authenticationProvider.indexOf( ":" ) + 1;
        int     dbNameEndIdx = _authenticateDatabaseOperationsLocally ?
            authenticationProvider.lastIndexOf( ":" )
            : authenticationProvider.length();

        if ( dbNameEndIdx > dbNameStartIdx )
        {
            _credentialsDB = authenticationProvider.substring( dbNameStartIdx, dbNameEndIdx );

            if ( _credentialsDB.length() == 0 ) { _credentialsDB = null; }
        }
    }

    /**
     * <p>
     * Return true if AUTHENTICATION_PROVIDER_PARAMETER was well formatted.
     * The property must have designated some database as the authentication authority.
     * </p>
     */
    private boolean validAuthenticationProvider()
    {
        return (_credentialsDB != null) || _authenticateDatabaseOperationsLocally;
    }

	/**
	 * @see org.apache.derby.iapi.services.monitor.ModuleControl#boot
	 * @exception StandardException upon failure to load/boot the expected
	 * authentication service.
	 */
	public void boot(boolean create, Properties properties)
	  throws StandardException
    {
		// first perform the initialization in our superclass
		super.boot( create, properties );

        if ( !validAuthenticationProvider() )
        {
            throw StandardException.newException( SQLState.BAD_NATIVE_AUTH_SPEC );
        }

		// Initialize the MessageDigest class engine here
		// (we don't need to do that ideally, but there is some
		// overhead the first time it is instantiated.
		try {
			MessageDigest digestAlgorithm = MessageDigest.getInstance("SHA-1");
			digestAlgorithm.reset();

		} catch (NoSuchAlgorithmException nsae) {
			throw Monitor.exceptionStartingModule(nsae);
		}

        // bootstrap the creation of the initial username/password when the dbo creates a credentials db
        if ( create && authenticatingInThisService( getCanonicalServiceName() ) ) { _creatingCredentialsDB = true; }
        else { _creatingCredentialsDB = false; }

		// Set ourselves as being ready, having loaded the proper
		// authentication scheme for this service
		//
		this.setAuthenticationService(this);
	}

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // UserAuthenticator BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    /** Override behavior in superclass */
    public  String  getSystemCredentialsDatabaseName()    { return _credentialsDB; }
    /** Override behavior in superclass */

	/**
	 * Authenticate the passed-in user's credentials.
	 *
	 * @param userName		The user's name used to connect to JBMS system
	 * @param userPassword	The user's password used to connect to JBMS system
	 * @param databaseName	The database which the user wants to connect to.
	 * @param info			Additional jdbc connection info.
	 */
	public boolean	authenticateUser
        (
         String userName,
         String userPassword,
         String databaseName,
         Properties info
         )
        throws SQLException
	{
        try {
            // No "guest" user
            if ( userName == null ) { return false; }

            //
            // We must handle these cases:
            //
            // 1) Database name is null. This means that we are authenticating a system-wide
            // operation. The authentication must be done by the system-wide credentials database.
            //
            // 2) Database name is not null and authentication is NOT specified as local.
            // This means that we are authenticating a database-specific operation
            // in the system-wide credentials database. There are two subcases:
            //
            // 2a) The current database is NOT the credentials database. This reduces to case (1) above:
            // authentication must be performed in another database.
            //
            // 2b) The current database IS the credentials database. This reduces to case (3) below:
            // authentication must be performed in this database.
            //
            // 3) Database name is not null and authentication IS being performed locally in this database.
            // This means that we are authenticating a database-specific operation and performing the
            // authentication in this database.
            //

            if ( (databaseName == null) || !authenticatingInThisDatabase( databaseName ) )
            {
                return authenticateRemotely(  userName, userPassword, databaseName );
            }
            else
            {
                return authenticateLocally( userName, userPassword, databaseName );
            }
        }
        catch (StandardException se)
        {
            throw Util.generateCsSQLException(se);
        }
	}

    /**
     * <p>
     * Return true if we are authenticating in this database.
     * </p>
     */
    private boolean authenticatingInThisDatabase( String userVisibleDatabaseName )
        throws StandardException
    {
        return authenticatingInThisService( Monitor.getMonitor().getCanonicalServiceName( userVisibleDatabaseName ) );
    }

    /**
     * <p>
     * Return true if we are authenticating in this service.
     * </p>
     */
    private boolean authenticatingInThisService( String canonicalDatabaseName )
        throws StandardException
    {
        if ( _authenticateDatabaseOperationsLocally ) { return true; }
        else { return isCredentialsService( canonicalDatabaseName ); }
    }

    /**
     * <p>
     * Return true if the passed in service is the credentials database.
     * </p>
     */
    private boolean isCredentialsService( String canonicalDatabaseName )
        throws StandardException
    {
        String  canonicalCredentialsDBName = getCanonicalServiceName( _credentialsDB );

        String canonicalDB = Monitor.getMonitor().getCanonicalServiceName( canonicalDatabaseName );

        if ( canonicalCredentialsDBName == null ) { return false; }
        else { return canonicalCredentialsDBName.equals( canonicalDatabaseName ); }
    }

    /** Get the canonical name of the current database service */
    private String  getCanonicalServiceName()
        throws StandardException
    {
        return getCanonicalServiceName( getServiceName() );
    }

    /** Turn a service name into its normalized, standard form */
    private String  getCanonicalServiceName( String rawName )
        throws StandardException
    {
        return Monitor.getMonitor().getCanonicalServiceName( rawName );
    }

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // AUTHENTICATE REMOTELY
    //
    ///////////////////////////////////////////////////////////////////////////////////

	/**
	 * Authenticate the passed-in credentials against another Derby database. This is done
     * by getting a connection to the credentials database using the supplied username
     * and password. If the connection attempts succeeds, then authentication succeeds.
	 *
	 * @param userName		The user's name used to connect to JBMS system
	 * @param userPassword	The user's password used to connect to JBMS system
	 * @param databaseName	The database which the user wants to connect to.
	 */
	private boolean	authenticateRemotely
        (
         String userName,
         String userPassword,
         String databaseName
         )
        throws StandardException
	{
        // this catches the case when someone specifies derby.authentication.provider=NATIVE::LOCAL
        // at the system level
        if ( _credentialsDB == null )
        {
            throw StandardException.newException( SQLState.BAD_NATIVE_AUTH_SPEC );
        }
        
        String      dataSourceName = JVMInfo.J2ME ?
            "org.apache.derby.jdbc.EmbeddedSimpleDataSource" :
            "org.apache.derby.jdbc.EmbeddedDataSource";

        try {
            DataSource  dataSource = (DataSource) Class.forName( dataSourceName ).newInstance();

            callDataSourceSetter( dataSource, "setDatabaseName", _credentialsDB );
            callDataSourceSetter( dataSource, "setUser", userName );
            callDataSourceSetter( dataSource, "setPassword", userPassword );

            Connection  conn = dataSource.getConnection();
            conn.close();
        }
        catch (ClassNotFoundException cnfe) { throw wrap( cnfe ); }
        catch (InstantiationException ie) { throw wrap( ie ); }
        catch (IllegalAccessException ie) { throw wrap( ie ); }
        catch (SQLException se)
        {
            String  sqlState = se.getSQLState();

            if ( SQLState.LOGIN_FAILED.equals( sqlState ) ) { return false; }
            else if ( SQLState.DATABASE_NOT_FOUND.startsWith( sqlState ) )
            {
                throw StandardException.newException( SQLState.MISSING_CREDENTIALS_DB, _credentialsDB );
            }
            else { throw wrap( se ); }
        }

        // If we get here, then we successfully connected to the credentials database. Hooray.
        return true;
    }
    /** Call a setter method on a DataSource via reflection */
    private void callDataSourceSetter( DataSource ds, String methodName, String value )
        throws StandardException
    {
        try {
            ds.getClass().getMethod( methodName, new Class[] { String.class } ).invoke( ds, new Object[] { value } );
        } catch (Exception e)  { throw wrap( e ); }   
    }
    private StandardException wrap( Throwable t )   { return StandardException.plainWrapException( t ); }
    
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // AUTHENTICATE LOCALLY
    //
    ///////////////////////////////////////////////////////////////////////////////////

	/**
	 * Authenticate the passed-in credentials against the local database.
	 *
	 * @param userName		The user's name used to connect to JBMS system
	 * @param userPassword	The user's password used to connect to JBMS system
	 * @param databaseName	The database which the user wants to connect to.
	 */
	private boolean	authenticateLocally
        (
         String userName,
         String userPassword,
         String databaseName
         )
        throws StandardException, SQLException
	{
        userName = IdUtil.getUserAuthorizationId( userName ) ;

        //
        // Special bootstrap code. If we are creating a credentials database, then
        // we store the DBO's initial credentials in it. We also turn on NATIVE LOCAL authentication
        // forever.
        //
        if ( _creatingCredentialsDB )
        {
            _creatingCredentialsDB = false;
            
            TransactionController   tc = getTransaction();
            
            SystemProcedures.addUser( userName, userPassword, tc );
            
            tc.setProperty
                ( Property.AUTHENTICATION_PROVIDER_PARAMETER, Property.AUTHENTICATION_PROVIDER_NATIVE_LOCAL, true );
            tc.commit();
            
            return true;
        }
        
        //
        // we expect to find a data dictionary
        //
        DataDictionary      dd = (DataDictionary) Monitor.getServiceModule( this, DataDictionary.MODULE );        
        UserDescriptor      userDescriptor = dd.getUser( userName );
        
        if ( userDescriptor == null )   { return false; }
        
        PasswordHasher      hasher = new PasswordHasher( userDescriptor.getHashingScheme() );
        char[]                     candidatePassword = hasher.hashPasswordIntoString( userName, userPassword ).toCharArray();
        char[]                     actualPassword = userDescriptor.getAndZeroPassword();
        
        if ( (candidatePassword == null) || (actualPassword == null)) { return false; }
        if ( candidatePassword.length != actualPassword.length ) { return false; }
        
        for ( int i = 0; i < candidatePassword.length; i++ )
        {
            if ( candidatePassword[ i ] != actualPassword[ i ] ) { return false; }
        }
        
        Arrays.fill( candidatePassword, (char) 0 );
        Arrays.fill( actualPassword, (char) 0 );
        
        return true;
    }
    
}
