/*

   Derby - Class org.apache.derby.impl.sql.compile.XMLOptTrace

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
package org.apache.derby.impl.sql.compile;

import java.io.PrintWriter;
import java.util.Date;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.sql.compile.AccessPath;
import org.apache.derby.iapi.sql.compile.CostEstimate;
import org.apache.derby.iapi.sql.compile.JoinStrategy;
import org.apache.derby.iapi.sql.compile.OptTrace;
import org.apache.derby.iapi.sql.compile.Optimizable;
import org.apache.derby.iapi.sql.compile.OptimizableList;
import org.apache.derby.iapi.sql.compile.Optimizer;
import org.apache.derby.iapi.sql.compile.RequiredRowOrdering;
import org.apache.derby.iapi.sql.dictionary.AliasDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.dictionary.ConglomerateDescriptor;
import org.apache.derby.iapi.util.IdUtil;
import org.apache.derby.iapi.util.JBitSet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Optimizer tracer which produces output in an xml format.
 */
class   XMLOptTrace implements  OptTrace
{
    ////////////////////////////////////////////////////////////////////////
    //
    //	CONSTANTS
    //
    ////////////////////////////////////////////////////////////////////////

    // statement tags
    private static  final   String  STMT = "statement";
    private static  final   String  STMT_ID = "stmtID";
    private static  final   String  STMT_TEXT = "stmtText";

    // query block tags
    private static  final   String  QBLOCK = "queryBlock";
    private static  final   String  QBLOCK_OPTIMIZER_ID = "qbOptimizerID";
    private static  final   String  QBLOCK_START_TIME = "qbStartTime";
    private static  final   String  QBLOCK_ID = "qbID";
    private static  final   String  QBLOCK_OPTIMIZABLE = "qbOptimizable";
    private static  final   String  QBLOCK_OPT_TABLE_NUMBER = "qboTableNumber";
    private static  final   String  QBLOCK_TIMEOUT = "qbTimeout";
    private static  final   String  QBLOCK_VACUOUS = "qbVacuous";
    private static  final   String  QBLOCK_SORT_COST = "qbSortCost";
    private static  final   String  QBLOCK_TOTAL_COST = "qbTotalCost";
    private static  final   String  QBLOCK_NO_BEST_PLAN = "qbNoBestPlan";
    private static  final   String  QBLOCK_SKIP = "qbSkip";

    // join order tags
    private static  final   String  JO = "joinOrder";
    private static  final   String  JO_COMPLETE = "joComplete";
    private static  final   String  JO_SLOT = "joSlot";

    // decoration tags
    private static  final   String  DECORATION = "decoration";
    private static  final   String  DECORATION_CONGLOM_NAME = "decConglomerateName";
    private static  final   String  DECORATION_KEY = "decKey";
    private static  final   String  DECORATION_TABLE_NAME = "decTableName";
    private static  final   String  DECORATION_JOIN_STRATEGY = "decJoinStrategy";
    private static  final   String  DECORATION_SKIP = "decSkip";
    private static  final   String  DECORATION_CONGLOM_COST = "decConglomerateCost";
    private static  final   String  DECORATION_FIRST_COLUMN_SELECTIVITY = "decExtraFirstColumnPreds";
    private static  final   String  DECORATION_EXTRA_START_STOP_SELECTIVITY = "decExtraFirstStartStopPreds";
    private static  final   String  DECORATION_START_STOP_SELECTIVITY = "decStartStopPred";
    private static  final   String  DECORATION_EXTRA_QUALIFIERS = "decExtraQualifiers";
    private static  final   String  DECORATION_EXTRA_NON_QUALIFIERS = "decExtraNonQualifiers";

    // skip tags
    private static  final   String  SKIP_REASON = "skipReason";

    // plan cost tags
    private static  final   String  PC = "planCost";
    private static  final   String  PC_TYPE = "pcType";
    private static  final   String  PC_COMPLETE = "pcComplete";
    private static  final   String  PC_AVOID_SORT= "pcAvoidSort";
    private static  final   String  PC_SUMMARY= "pcSummary";
    private static  final   String  PC_VERBOSE= "pcVerbose";

    // CostEstimate tags
    private static  final   String  CE_ESTIMATED_COST = "ceEstimatedCost";
    private static  final   String  CE_ROW_COUNT = "ceEstimatedRowCount";
    private static  final   String  CE_SINGLE_SCAN_ROW_COUNT = "ceSingleScanRowCount";

    // selectivity tags
    private static  final   String  SEL_COUNT = "selCount";
    private static  final   String  SEL_SELECTIVITY = "selSelectivity";

    // distinguish table function names from conglomerate names
    private static  final   String  TABLE_FUNCTION_FLAG = "()";
    
    //
    // Statement and view for declaring a table function which reads the planCost element.
    // This table function is an instance of the XmlVTI and assumes that you have
    // already declared an ArrayList user-type and an asList factory function for it.
    //
    static  final   String  PLAN_COST_VTI =
        "create function planCost\n" +
        "(\n" +
        "    xmlResourceName varchar( 32672 ),\n" +
        "    rowTag varchar( 32672 ),\n" +
        "    parentTags ArrayList,\n" +
        "    childTags ArrayList\n" +
        ")\n" +
        "returns table\n" +
        "(\n" +
        "    text varchar( 32672 ),\n" +
        "    stmtID    int,\n" +
        "    qbID   int,\n" +
        "    complete  boolean,\n" +
        "    summary   varchar( 32672 ),\n" +
        "    verbose   varchar( 32672 ),\n" +
        "    type        varchar( 50 ),\n" +
        "    estimatedCost        double,\n" +
        "    estimatedRowCount    bigint\n" +
        ")\n" +
        "language java parameter style derby_jdbc_result_set no sql\n" +
        "external name 'org.apache.derby.vti.XmlVTI.xmlVTI'\n";

    static  final   String  PLAN_COST_VIEW =
        "create view planCost as\n" +
        "select *\n" +
        "from table\n" +
        "(\n" +
        "    planCost\n" +
        "    (\n" +
        "        'FILE_URL',\n" +
        "        'planCost',\n" +
        "        asList( '" + STMT_TEXT + "', '" + STMT_ID + "', '" + QBLOCK_ID + "' ),\n" +
        "        asList( '" + PC_COMPLETE + "', '" + PC_SUMMARY + "', '" + PC_VERBOSE + "', '" + PC_TYPE + "', '" +
        CE_ESTIMATED_COST + "', '" + CE_ROW_COUNT + "' )\n" +
        "     )\n" +
        ") v\n";
        
    ////////////////////////////////////////////////////////////////////////
    //
    //	STATE
    //
    ////////////////////////////////////////////////////////////////////////

    private Document    _doc;
    private Element         _root;
    
    private Element         _currentStatement;
    private int                 _currentStatementID;

    private Element         _currentQuery;
    private int                 _currentQueryID;
    private OptimizableList _currentOptimizableList;
    private Element         _currentJoinsElement;
    private int[]              _currentJoinOrder;
    private Element         _currentBestPlan;

    // reset per join order
    private JoinStrategy    _currentDecorationStrategy;
    private Element         _currentDecoration;

    ////////////////////////////////////////////////////////////////////////
    //
    //	CONSTRUCTOR
    //
    ////////////////////////////////////////////////////////////////////////

    /** 0-arg constructor required by OptTrace contract */
    public  XMLOptTrace()
        throws ParserConfigurationException
    {
        _doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        _root = createElement( null, "optimizerTrace", null );
        _doc.appendChild( _root );
    }

    ////////////////////////////////////////////////////////////////////////
    //
    //	BEHAVIOR
    //
    ////////////////////////////////////////////////////////////////////////

    public  void    traceStartStatement( String statementText )
    {
        _currentStatementID++;
        _currentQueryID = 0;
        
        _currentStatement = createElement( _root, STMT, null );
        _currentStatement .setAttribute( STMT_ID, Integer.toString( _currentStatementID ) );

        _currentOptimizableList = null;
        _currentJoinsElement = null;
        _currentJoinOrder = null;

        _currentDecorationStrategy = null;
        _currentDecoration = null;

        _currentBestPlan = null;
        
        createElement( _currentStatement, STMT_TEXT, statementText );
    }
    
    public  void    traceStart( long timeOptimizationStarted, int optimizerID, OptimizableList optimizableList )
    {
        _currentQueryID++;
        _currentOptimizableList = optimizableList;
        _currentJoinOrder = null;

        _currentDecorationStrategy = null;
        _currentDecoration = null;

        _currentBestPlan = null;

        _currentQuery = createElement( _currentStatement, QBLOCK, null );
        _currentQuery.setAttribute( QBLOCK_OPTIMIZER_ID, Integer.toString( optimizerID ) );
        _currentQuery.setAttribute( QBLOCK_START_TIME, formatTimestamp( timeOptimizationStarted ) );
        _currentQuery.setAttribute( QBLOCK_ID, Integer.toString( _currentQueryID ) );

        if ( _currentOptimizableList != null )
        {
            for ( int i = 0; i < _currentOptimizableList.size(); i++ )
            {
                Optimizable opt = _currentOptimizableList.getOptimizable( i );
                Element optElement = createElement
                    ( _currentQuery, QBLOCK_OPTIMIZABLE, getOptimizableName( opt ).getFullSQLName() );
                optElement.setAttribute( QBLOCK_OPT_TABLE_NUMBER, Integer.toString( opt.getTableNumber() ) );
            }
        }
    }
    
    public  void    traceTimeout( long currentTime, CostEstimate bestCost )
    {
        Element timeout = createElement( _currentQuery, QBLOCK_TIMEOUT, null );
        formatCost( timeout, bestCost );
    }
    
    public  void    traceVacuous()
    {
        createElement( _currentQuery, QBLOCK_VACUOUS, null );
    }
    
    public  void    traceCompleteJoinOrder()
    {
        if ( _currentJoinsElement != null )    { _currentJoinsElement.setAttribute( JO_COMPLETE, "true" ); }
    }
    
    public  void    traceSortCost( CostEstimate sortCost, CostEstimate currentCost )
    {
        Element sc = createElement( _currentQuery, QBLOCK_SORT_COST, null );
        formatCost( sc, sortCost );
            
        Element tcis = createElement( _currentQuery, QBLOCK_TOTAL_COST, null );
        formatCost( tcis, currentCost );
    }
    
    public  void    traceNoBestPlan()
    {
        createElement( _currentQuery, QBLOCK_NO_BEST_PLAN, null );
    }
    
    public  void    traceModifyingAccessPaths( int optimizerID ) {}
    
    public  void    traceShortCircuiting( boolean timeExceeded, Optimizable thisOpt, int joinPosition ) {}
    
    public  void    traceSkippingJoinOrder( int nextOptimizable, int joinPosition, int[] proposedJoinOrder, JBitSet assignedTableMap )
    {
        Optimizable opt = _currentOptimizableList.getOptimizable( nextOptimizable );

        Element skip = formatSkip
            (
             _currentQuery, QBLOCK_SKIP,
             "Useless join order. " + getOptimizableName( opt ).getFullSQLName() + " depends on tables after it in the join order"
             );
        formatJoinOrder( skip, proposedJoinOrder );
    }
    
    public  void    traceIllegalUserJoinOrder() {}
    public  void    traceUserJoinOrderOptimized() {}
    
    public  void    traceJoinOrderConsideration( int joinPosition, int[] proposedJoinOrder, JBitSet assignedTableMap )
    {
        _currentJoinsElement = createElement( _currentQuery, JO, null );
        _currentJoinOrder = proposedJoinOrder;

        _currentDecorationStrategy = null;
        _currentDecoration = null;

        formatJoinOrder( _currentJoinsElement, proposedJoinOrder );
    }

    public  void    traceCostWithoutSortAvoidance( CostEstimate currentCost )
    {
        formatPlanCost
            (
             _currentJoinsElement, "withoutSortAvoidance",
             _currentJoinOrder, Optimizer.NORMAL_PLAN, currentCost
             );
    }
    
    public  void    traceCostWithSortAvoidance( CostEstimate currentSortAvoidanceCost )
    {
        formatPlanCost
            (
             _currentJoinsElement, "withSortAvoidance",
             _currentJoinOrder, Optimizer.SORT_AVOIDANCE_PLAN, currentSortAvoidanceCost
             );
    }
    
    public  void    traceCurrentPlanAvoidsSort( CostEstimate bestCost, CostEstimate currentSortAvoidanceCost ) {}
    public  void    traceCheapestPlanSoFar( int planType, CostEstimate currentCost ) {}
    public  void    traceSortNeededForOrdering( int planType, RequiredRowOrdering requiredRowOrdering ) {}
    
    public  void    traceRememberingBestJoinOrder
        ( int joinPosition, int[] bestJoinOrder, int planType, CostEstimate planCost, JBitSet assignedTableMap )
    {
        if ( _currentBestPlan != null ) { _currentQuery.removeChild( _currentBestPlan ); }
        _currentBestPlan = formatPlanCost( _currentQuery, "bestPlan", bestJoinOrder, planType, planCost );
    }
    
    public  void    traceSkippingBecauseTooMuchMemory( int maxMemoryPerTable )
    {
        formatSkip( _currentDecoration, DECORATION_SKIP, "Exceeds limit on memory per table: " + maxMemoryPerTable );
    }
    
    public  void    traceCostOfNScans( int tableNumber, double rowCount, CostEstimate cost ) {}
    
    public  void    traceSkipUnmaterializableHashJoin()
    {
        formatSkip( _currentDecoration, DECORATION_SKIP, "Hash strategy not possible because table is not materializable" );
    }
    
    public  void    traceSkipHashJoinNoHashKeys()
    {
        formatSkip( _currentDecoration, DECORATION_SKIP, "No hash keys" );
    }
    
    public  void    traceHashKeyColumns( int[] hashKeyColumns ) {}
    public  void    traceOptimizingJoinNode() {}
    
    public  void    traceConsideringJoinStrategy( JoinStrategy js, int tableNumber )
    {
        _currentDecorationStrategy = js;
    }
    
    public  void    traceRememberingBestAccessPath( AccessPath accessPath, int tableNumber, int planType ) {}
    public  void    traceNoMoreConglomerates( int tableNumber ) {}
    
    public  void    traceConsideringConglomerate( ConglomerateDescriptor cd, int tableNumber )
    {
        Optimizable opt = getOptimizable( tableNumber );
        
        _currentDecoration = createElement( _currentJoinsElement, DECORATION, null );

        _currentDecoration.setAttribute( DECORATION_CONGLOM_NAME, cd.getConglomerateName() );
        _currentDecoration.setAttribute( DECORATION_TABLE_NAME, getOptimizableName( opt ).toString() );
        _currentDecoration.setAttribute( DECORATION_JOIN_STRATEGY, _currentDecorationStrategy.getName() );
        
		String[]	columnNames = cd.getColumnNames();

		if ( cd.isIndex() && (columnNames != null) )
		{
			int[]   keyColumns = cd.getIndexDescriptor().baseColumnPositions();

            for ( int i = 0; i < keyColumns.length; i++ )
            {
                createElement( _currentDecoration, DECORATION_KEY, columnNames[ keyColumns[ i ] - 1 ] );
            }
		}
    }
    
    public  void    traceScanningHeapWithUniqueKey() {}
    public  void    traceAddingUnorderedOptimizable( int predicateCount ) {}
    public  void    traceChangingAccessPathForTable( int tableNumber ) {}
    public  void    traceNoStartStopPosition() {}
    public  void    traceNonCoveringIndexCost( double cost, int tableNumber ) {}
    public  void    traceConstantStartStopPositions() {}
    public  void    traceEstimatingCostOfConglomerate( ConglomerateDescriptor cd, int tableNumber ) {}
    public  void    traceLookingForSpecifiedIndex( String indexName, int tableNumber ) {}
    public  void    traceSingleMatchedRowCost( double cost, int tableNumber ) {}
    public  void    traceCostIncludingExtra1stColumnSelectivity( CostEstimate cost, int tableNumber ) {}
    public  void    traceNextAccessPath( String baseTable, int predicateCount ) {}
    public  void    traceCostIncludingExtraStartStop( CostEstimate cost, int tableNumber ) {}
    public  void    traceCostIncludingExtraQualifierSelectivity( CostEstimate cost, int tableNumber ) {}
    public  void    traceCostIncludingExtraNonQualifierSelectivity( CostEstimate cost, int tableNumber ) {}
    public  void    traceCostOfNoncoveringIndex( CostEstimate cost, int tableNumber ) {}
    public  void    traceRememberingJoinStrategy( JoinStrategy joinStrategy, int tableNumber ) {}
    public  void    traceRememberingBestAccessPathSubstring( AccessPath ap, int tableNumber ) {}
    public  void    traceRememberingBestSortAvoidanceAccessPathSubstring( AccessPath ap, int tableNumber ) {}
    public  void    traceRememberingBestUnknownAccessPathSubstring( AccessPath ap, int tableNumber ) {}
    
    public  void    traceCostOfConglomerateScan
        (
         int    tableNumber,
         ConglomerateDescriptor cd,
         CostEstimate   costEstimate,
         int    numExtraFirstColumnPreds,
         double    extraFirstColumnSelectivity,
         int    numExtraStartStopPreds,
         double    extraStartStopSelectivity,
         int    startStopPredCount,
         double    statStartStopSelectivity,
         int    numExtraQualifiers,
         double    extraQualifierSelectivity,
         int    numExtraNonQualifiers,
         double    extraNonQualifierSelectivity
         )
    {
        Element cost = createElement( _currentDecoration, DECORATION_CONGLOM_COST, null );
        cost.setAttribute( "name", cd.getConglomerateName() );

        formatCost( cost, costEstimate );
        formatSelectivity( cost, DECORATION_FIRST_COLUMN_SELECTIVITY, numExtraFirstColumnPreds, extraFirstColumnSelectivity );
        formatSelectivity( cost, DECORATION_EXTRA_START_STOP_SELECTIVITY, numExtraStartStopPreds, extraStartStopSelectivity );
        formatSelectivity( cost, DECORATION_START_STOP_SELECTIVITY, startStopPredCount, statStartStopSelectivity );
        formatSelectivity( cost, DECORATION_EXTRA_QUALIFIERS, numExtraQualifiers, extraQualifierSelectivity );
        formatSelectivity( cost, DECORATION_EXTRA_NON_QUALIFIERS, numExtraNonQualifiers, extraNonQualifierSelectivity );
    }
    
    public  void    traceCostIncludingCompositeSelectivityFromStats( CostEstimate cost, int tableNumber ) {}
    public  void    traceCompositeSelectivityFromStatistics( double statCompositeSelectivity ) {}
    public  void    traceCostIncludingStatsForIndex( CostEstimate cost, int tableNumber ) {}

    public  void    printToWriter( PrintWriter out )
    {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource( _doc );
            StreamResult result = new StreamResult( out );

            // pretty-print
            transformer.setOutputProperty( OutputKeys.OMIT_XML_DECLARATION, "no" );
            transformer.setOutputProperty( OutputKeys.METHOD, "xml" );
            transformer.setOutputProperty( OutputKeys.INDENT, "yes" );
            transformer.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
            transformer.setOutputProperty( "{http://xml.apache.org/xslt}indent-amount", "4" );
            
            transformer.transform( source, result );
            
        }   catch (Throwable t) { printThrowable( t ); }
    }

    ////////////////////////////////////////////////////////////////////////
    //
    //	MINIONS
    //
    ////////////////////////////////////////////////////////////////////////

    /** Get the Optimizable with the given tableNumber */
    private Optimizable getOptimizable( int tableNumber )
    {
        for ( int i = 0; i < _currentOptimizableList.size(); i++ )
        {
            Optimizable candidate = _currentOptimizableList.getOptimizable( i );
            
            if ( tableNumber == candidate.getTableNumber() )    { return candidate; }
        }

        return null;
    }

    /** Get the name of an optimizable */
    private TableName    getOptimizableName( Optimizable optimizable )
    {
        ContextManager  cm = ((QueryTreeNode) optimizable).getContextManager();
        
        try {
            if ( isBaseTable( optimizable ) )
            {
                ProjectRestrictNode prn = (ProjectRestrictNode) optimizable;
                TableDescriptor td = 
                    ((FromBaseTable) prn.getChildResult()).getTableDescriptor();
                return makeTableName( td.getSchemaName(), td.getName(), cm );
            }
            else if ( isTableFunction( optimizable ) )
            {
                ProjectRestrictNode prn = (ProjectRestrictNode) optimizable;
                AliasDescriptor ad =
                    ((StaticMethodCallNode) ((FromVTI) prn.getChildResult()).
                        getMethodCall() ).ad;
                return makeTableName( ad.getSchemaName(), ad.getName(), cm );
            }
            else if ( isFromTable( optimizable ) )
            {
                return ((FromTable) ((ProjectRestrictNode) optimizable).getChildResult()).getTableName();
            }
        }
        catch (StandardException e)
        {
            // Technically, an exception could occur here if the table name
            // was not previously bound and if an error occured while binding it.
            // But the optimizable should have been bound long before optimization,
            // so this should not be a problem.
        }

        String  nodeClass = optimizable.getClass().getName();
        String  unqualifiedName = nodeClass.substring( nodeClass.lastIndexOf( "." ) + 1 );

        return makeTableName( null, unqualifiedName, cm );
    }

    /** Return true if the optimizable is a base table */
    private boolean isBaseTable( Optimizable optimizable )
    {
        if ( !( optimizable instanceof ProjectRestrictNode ) ) { return false; }

        ResultSetNode   rsn = ((ProjectRestrictNode) optimizable).getChildResult();

        return ( rsn instanceof FromBaseTable );
    }

    /** Return true if the optimizable is a FromTable */
    private boolean isFromTable( Optimizable optimizable )
    {
        if ( !( optimizable instanceof ProjectRestrictNode ) ) { return false; }

        ResultSetNode   rsn = ((ProjectRestrictNode) optimizable).getChildResult();

        return ( rsn instanceof FromTable );
    }

    /** Return true if the optimizable is a table function */
    private boolean isTableFunction( Optimizable optimizable )
    {
        if ( !( optimizable instanceof ProjectRestrictNode ) ) { return false; }

        ResultSetNode   rsn = ((ProjectRestrictNode) optimizable).getChildResult();
        if ( !( rsn instanceof FromVTI ) ) { return false; }

        return ( ((FromVTI) rsn).getMethodCall() instanceof StaticMethodCallNode );
    }

    /** Make a TableName */
    private TableName   makeTableName(
            String schemaName, String unqualifiedName, ContextManager cm )
    {
        TableName result = new TableName(schemaName, unqualifiedName, cm);

        return result;
    }

    /** Print an exception to the log file */
    private void    printThrowable( Throwable t )
    {
        t.printStackTrace( Monitor.getStream().getPrintWriter() );
    }

    /** Create an element and add it to a parent */
    private Element createElement( Element parent, String tag, String content )
    {
        Element child = null;
        
        try {
            child = _doc.createElement( tag );
            if ( parent != null) { parent.appendChild( child ); }
            if ( content != null ) { child.setTextContent( content ); }
        }
        catch (Throwable t) { printThrowable( t ); }

        return child;
    }

    /** Turn a timestamp into a human-readable string */
    private String  formatTimestamp( long timestamp ) { return (new Date( timestamp )).toString(); }

    /** Create an element explaining that we're skipping some processing */
    private Element formatSkip( Element parent, String skipTag, String reason )
    {
        Element skip = createElement( parent, skipTag, null );
        skip.setAttribute( SKIP_REASON, reason );

        return skip;
    }
    
    /** Turn a CostEstimate for a join order into a human-readable element */
    private Element formatPlanCost( Element parent, String type, int[] planOrder, int planType, CostEstimate raw )
    {
        Element cost = createElement( parent, PC, null );

        cost.setAttribute( PC_TYPE, type );
        if ( isComplete( planOrder ) ) { cost.setAttribute( PC_COMPLETE, "true" ); }
        if ( planType == Optimizer.SORT_AVOIDANCE_PLAN ) { cost.setAttribute( PC_AVOID_SORT, "true" ); }

        createElement( cost, PC_SUMMARY, formatPlanSummary( planOrder, planType, false ) );
        createElement( cost, PC_VERBOSE, formatPlanSummary( planOrder, planType, true ) );
        formatCost( cost, raw );

        return cost;
    }

    /** Return true if the join order has been completely filled in */
    private boolean isComplete( int[] joinOrder )
    {
        if ( joinOrder == null ) { return false; }
        if ( joinOrder.length < _currentOptimizableList.size() ) { return false; }

        for ( int i = 0; i < joinOrder.length; i++ )
        {
            if ( joinOrder[ i ] < 0 ) { return false; }
        }

        return true;
    }

    /** Format a CostEstimate as subelements of a parent */
    private void    formatCost( Element costElement, CostEstimate raw )
    {
        createElement( costElement, CE_ESTIMATED_COST, Double.toString( raw.getEstimatedCost() ) );
        createElement( costElement, CE_ROW_COUNT, Long.toString( raw.getEstimatedRowCount() ) );
        createElement( costElement, CE_SINGLE_SCAN_ROW_COUNT, Double.toString( raw.singleScanRowCount() ) );
    }

    /** Format selectivity subelement */
    private void    formatSelectivity( Element parent, String tag, int count, double selectivity )
    {
        Element child = createElement( parent, tag, null );
        child.setAttribute( SEL_COUNT, Integer.toString( count ) );
        child.setAttribute( SEL_SELECTIVITY, Double.toString( selectivity ) );
    }

    /** Format a join order list */
    private void    formatJoinOrder( Element parent, int[] proposedJoinOrder )
    {
        if ( proposedJoinOrder != null )
        {
            for ( int idx = 0; idx < proposedJoinOrder.length; idx++ )
            {
                int     optimizableNumber = proposedJoinOrder[ idx ];
                if ( optimizableNumber >= 0 )
                {
                    Optimizable optimizable = _currentOptimizableList.getOptimizable( optimizableNumber );
                    createElement( parent, JO_SLOT, getOptimizableName( optimizable ).getFullSQLName() );
                }
            }
        }
    }


    /**
     * <p>
     * Produce a string representation of the plan being considered now.
     * The string has the following grammar:
     * </p>
     *
     * <pre>
     * join :== factor OP factor
     *
     * OP :== "*" | "#"
     *
     * factor :== factor | conglomerateName
     * </pre>
     */
    private String  formatPlanSummary( int[] planOrder, int planType, boolean verbose )
    {
        StringBuilder   buffer = new StringBuilder();
        boolean     avoidSort = (planType == Optimizer.SORT_AVOIDANCE_PLAN);

        // a negative optimizable number indicates the end of the plan
        int planLength = 0;
        for ( ; planLength < planOrder.length; planLength++ )
        {
            if ( planOrder[ planLength ] < 0 ) { break; }
        }

        // only add parentheses if there are more than 2 slots in the join order
        int     dontNeedParentheses = 2;
        int     lastParenthesizedIndex = planLength - dontNeedParentheses;
        for ( int i = 0; i < lastParenthesizedIndex; i++ ) { buffer.append( "(" ); }
        
        for ( int i = 0; i < planLength; i++ )
        {
            int     listIndex = planOrder[ i ];

            if ( listIndex >= _currentOptimizableList.size() )
            {
                // should never happen!
                buffer.append( "{ UNKNOWN LIST INDEX " + listIndex + " } " );
                continue;
            }

            Optimizable optimizable = _currentOptimizableList.getOptimizable( listIndex );
            
            AccessPath  ap = avoidSort ?
                optimizable.getBestSortAvoidancePath() : optimizable.getBestAccessPath();
            ConglomerateDescriptor  cd = ap.getConglomerateDescriptor();
            String  conglomerateName = getSQLName( optimizable, cd, verbose );
            JoinStrategy    js = ap.getJoinStrategy();

            //
            // The very first optimizable in the join order obiously doesn't join
            // to anything before it. For that reason, its join strategy is always
            // NESTED_LOOP. We can just assume that and not clutter up the
            // representation with vacuous information.
            //
            if ( i > 0 ) { buffer.append( " " + js.getOperatorSymbol() + " " ); }
            
            buffer.append( conglomerateName );
            if ( (i > 0) && (i <= lastParenthesizedIndex) ) { buffer.append( ")" ); }
        }

        return buffer.toString();
    }

    /**
     * <p>
     * Get a human-readable name for a conglomerate.
     * </p>
     */
    private String  getSQLName( Optimizable optimizable, ConglomerateDescriptor cd, boolean verbose )
    {
        if ( !verbose && (cd != null) )
        {
            String  schemaName = getOptimizableName( optimizable ).getSchemaName();
            String  conglomerateName = cd.getConglomerateName();

            return IdUtil.mkQualifiedName( schemaName, conglomerateName );
        }

        boolean isTableFunction = isTableFunction( optimizable );
        StringBuilder   buffer = new StringBuilder();
        buffer.append( getOptimizableName( optimizable ).getFullSQLName() );
        if ( isTableFunction ) { buffer.append( TABLE_FUNCTION_FLAG ); }
        
        if ( (cd != null) && cd.isIndex() )
        {
            buffer.append( "{" );
            String[]	columnNames = cd.getColumnNames();
            
            if ( columnNames != null )
            {
                int[]   keyColumns = cd.getIndexDescriptor().baseColumnPositions();
                
                for ( int i = 0; i < keyColumns.length; i++ )
                {
                    if ( i > 0 ) { buffer.append( "," ); }
                    buffer.append( columnNames[ keyColumns[ i ] - 1 ] );
                }
            }
            buffer.append( "}" );
        }

        return buffer.toString();
    }
    
}