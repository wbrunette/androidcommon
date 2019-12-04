/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.views;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.opendatakit.activities.IOdkDataActivity;
import org.opendatakit.consts.IntentConsts;
import org.opendatakit.database.queries.ArbitraryQuery;
import org.opendatakit.database.queries.BindArgs;
import org.opendatakit.database.queries.ResumableQuery;
import org.opendatakit.database.queries.SimpleQuery;
import org.opendatakit.database.queries.SingleRowQuery;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.provider.DataTableColumns;
import org.opendatakit.utilities.ODKFileUtils;

import java.lang.ref.WeakReference;
import java.util.HashMap;

public class OdkData {

  public static final String descOrder = "DESC";

  public static class IntentKeys {

    public static final String ACTION_TABLE_ID = "actionTableId";
    /**
     * tables that have conflict rows
     */
    public static final String CONFLICT_TABLES = "conflictTables";
    /**
     * tables that have checkpoint rows
     */
    public static final String CHECKPOINT_TABLES = "checkpointTables";

    /**
     * for conflict resolution screens
     */
    public static final String TABLE_ID = IntentConsts.INTENT_KEY_TABLE_ID;
    /**
     * common for all activities
     */
    public static final String APP_NAME = IntentConsts.INTENT_KEY_APP_NAME;
    /**
     * Tells what time of view it should be
     * displaying.
     */
    public static final String TABLE_DISPLAY_VIEW_TYPE = "tableDisplayViewType";
    public static final String FILE_NAME = "filename";
    public static final String ROW_ID = "rowId";
    /**
     * The name of the graph view that should be displayed.
     */
    public static final String GRAPH_NAME = "graphName";
    public static final String ELEMENT_KEY = "elementKey";
    public static final String COLOR_RULE_TYPE = "colorRuleType";
    /**
     * The  that should be displayed when launching a
     */
    public static final String TABLE_PREFERENCE_FRAGMENT_TYPE = "tablePreferenceFragmentType";
    /**
     * Key to the where clause if this list view is to be opened with a more
     * complex query than permissible by the simple query object.
     */
    public static final String SQL_WHERE = "sqlWhereClause";
    /**
     * A JSON serialization of an array of objects for restricting the rows displayed in the table.
     */
    public static final String SQL_SELECTION_ARGS = "sqlSelectionArgs";
    /**
     * An array of strings giving the group by columns. What was formerly
     * 'overview' mode is a non-null groupBy list.
     */
    public static final String SQL_GROUP_BY_ARGS = "sqlGroupByArgs";
    /**
     * The having clause, if present
     */
    public static final String SQL_HAVING = "sqlHavingClause";
    /**
     * The order by column. NOTE: restricted to a single column
     */
    public static final String SQL_ORDER_BY_ELEMENT_KEY = "sqlOrderByElementKey";
    /**
     * The order by direction (ASC or DESC)
     */
    public static final String SQL_ORDER_BY_DIRECTION = "sqlOrderByDirection";
    /**
     * The metaDataRev of the metadata for the tableId that the Javascript
     * side has. This may be null.
     */
    public static final String META_DATA_REV = "metaDataRev";
    /**
     * The sql command if using an arbitrary query
     */
    public static final String SQL_COMMAND = "sqlCommand";
    /**
     * The type of query stored in this intent
     */
    public static final String QUERY_TYPE = "queryType";
  }

  public static final class QueryTypes {
    public static final String SIMPLE_QUERY = "SimpleQuery";
    public static final String ARBITRARY_QUERY = "ArbitraryQuery";
  }

  private WeakReference<IOdkWebView> mWebView;
  private IOdkDataActivity mActivity;

  private static final String TAG = OdkData.class.getSimpleName();

  private ExecutorContext context;

  public OdkData(IOdkDataActivity activity, IOdkWebView webView) {
    mActivity = activity;
    mWebView = new WeakReference<IOdkWebView>(webView);
    // change to support multiple data objects within a single webpage
    context = ExecutorContext.getContext(mActivity);
  }

  public boolean isInactive() {
    return (mWebView.get() == null) || (mWebView.get().isInactive());
  }

  public synchronized void refreshContext() {
    if (!context.isAlive()) {
      context = ExecutorContext.getContext(mActivity);
    }
  }

  public synchronized void shutdownContext() {
    context.shutdownWorker();
  }

  private void logDebug(String loggingString) {
    WebLogger.getLogger(this.mActivity.getAppName()).d("odkData", loggingString);
  }

  private void queueRequest(ExecutorRequest request) {
    context.queueRequest(request);
  }

  public OdkDataIf getJavascriptInterfaceWithWeakReference() {
    return new OdkDataIf(this);
  }

  /**
   * Access the result of a request
   *
   * @return null if there is no result, otherwise the responseJSON of the last action
   */
  public String getResponseJSON() {
    return mActivity.getResponseJSON(getFragmentID());
  }

  /**
   * Get the data for the view once the user is ready for it.
   * When the user chooses to launch a detail, list, or map view
   * they will have to call this via the JS API with success and
   * failure callback functions to manipulate the data for their views
   */
  public void getViewData(String callbackJSON, Integer limit, Integer offset) {
    logDebug("getViewData");

    ResumableQuery queryParams = this.mActivity.getViewQuery(getFragmentID());

    if (queryParams == null) {
      return;
    }

    ExecutorRequest request;
    if (queryParams instanceof ArbitraryQuery) {
      ArbitraryQuery query = (ArbitraryQuery) queryParams;

      request = new ExecutorRequest(ExecutorRequestType.ARBITRARY_QUERY, query.getTableId(),
              query.getSqlCommand(),
              query.getSqlBindArgs(), query.getSqlLimit(), query.getSqlOffset(),
              null, callbackJSON, getFragmentID());
    } else if (queryParams instanceof SingleRowQuery &&
        ((SingleRowQuery) queryParams).getRowId() != null &&
        !((SingleRowQuery) queryParams).getRowId().isEmpty()) {
      // If we are a single row query but haven't set row id, this is actually a simplequery and
      // will get handled in the else if
      SingleRowQuery query = (SingleRowQuery) queryParams;

      BindArgs bindArgs = new BindArgs(new Object[] { query.getRowId() });
      request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_QUERY, query.getTableId(),
              DataTableColumns.ID + "=?", bindArgs, null, null, DataTableColumns.SAVEPOINT_TIMESTAMP,
              descOrder, limit, offset, true, null, callbackJSON, getFragmentID());
    } else if (queryParams instanceof SimpleQuery || queryParams instanceof SingleRowQuery) {
      SimpleQuery query = (SimpleQuery) queryParams;

      // TODO: FIX THIS! This is a workaround for the fact that orderbyCol and orderByDir are string
      // arrays in the database layer and single strings in the javascript layer. I'm reusing the
      // query objects from the database layer. The changes need to be plumbed up so that everybody
      // always uses string arrays for orderby* fields all th way up to the Javascript.
      String[] queryOrderByCol = query.getOrderByColNames();
      String[] queryOrderByDir = query.getOrderByDirections();
      String orderByCol = (queryOrderByCol == null || queryOrderByCol.length == 0) ?
          null : queryOrderByCol[0];
      String orderByDir = (queryOrderByDir == null || queryOrderByDir.length == 0) ?
          null : queryOrderByDir[0];


      request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_QUERY, query.getTableId(),
              query.getWhereClause(), query.getSqlBindArgs(), query.getGroupByArgs(),
              query.getHavingClause(), orderByCol, orderByDir, limit, offset, true, null,
              callbackJSON, getFragmentID());
    } else {
      // Invalid state
      return;
    }
    queueRequest(request);
  }

  private String getFragmentID() {
    IOdkWebView webView = mWebView.get();
    if (webView == null) {
      // should not occur unless we are tearing down the view
      WebLogger.getLogger(mActivity.getAppName()).w(TAG, "null webView");
      return null;
    }

    return webView.getContainerFragmentID();
  }

  /**
   * Get all the roles granted to this user by the server.
   *
   * @param callbackJSON
   */
  public void getRoles(String callbackJSON) {
    logDebug("getRoles");
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.GET_ROLES_LIST,
        callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Get the default group of the current user as assigned to this user by the server.
   *
   * @param callbackJSON
   */
  public void getDefaultGroup(String callbackJSON) {
    logDebug("getDefaultGroup");
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.GET_DEFAULT_GROUP,
        callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Get all the users on the server and their roles.
   *
   * @param callbackJSON
   */
  public void getUsers(String callbackJSON) {
    logDebug("getUsers");
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.GET_USERS_LIST,
        callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Get all the tableIds in the system.
   *
   * @param callbackJSON
   */
  public void getAllTableIds(String callbackJSON) {
    logDebug("getAllTableIds");
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.GET_ALL_TABLE_IDS,
        callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Query the database using sql.
   *
   * @param tableId                 The table being queried. This is a user-defined table.
   * @param whereClause             The where clause for the query
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values (including any in
   *                          the having clause)
   * @param groupBy                 The array of columns to group by
   * @param having                  The having clause
   * @param orderByElementKey       The column to order by
   * @param orderByDirection        'ASC' or 'DESC' ordering
   * @param limit         The maximum number of rows to return (null returns all)
   * @param offset        The offset into the result set of the first row to return (null ok)
   * @param includeKeyValueStoreMap true if the keyValueStoreMap should be returned
   * @param callbackJSON            The JSON object used by the JS layer to recover the callback function
   *                                that can process the response
   */
  public void query(String tableId, String whereClause, String sqlBindParamsJSON, String[] groupBy,
      String having, String orderByElementKey, String orderByDirection,
      Integer limit, Integer offset, boolean includeKeyValueStoreMap, String metaDataRev, String callbackJSON) {
    logDebug("query: " + tableId + " whereClause: " + whereClause);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_QUERY,
            tableId, whereClause, bindArgs, groupBy, having, orderByElementKey, orderByDirection,
            limit, offset, includeKeyValueStoreMap, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Arbitrary SQL query
   *
   * @param tableId       The tableId whose metadata should be returned. If a result
   *                      column matches the column name in this tableId, then the data
   *                      type interpretations for that column will be applied to the result
   *                      column (e.g., integer, number, array, object conversions).
   * @param sqlCommand    The Select statement to issue. It can reference any table in the database,
   *                      including system tables.
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values (including any in
   *                          the having clause)
   * @param limit         The maximum number of rows to return (null returns all)
   * @param offset        The offset into the result set of the first row to return (null ok)
   * @param callbackJSON  The JSON object used by the JS layer to recover the callback function
   *                      that can process the response
   * @return see description in class header
   */
  public void arbitraryQuery(String tableId, String sqlCommand, String sqlBindParamsJSON,
      Integer limit, Integer offset, String metaDataRev, String callbackJSON) {
    logDebug("arbitraryQuery: " + tableId + " sqlCommand: " + sqlCommand);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);

    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.ARBITRARY_QUERY, tableId,
            sqlCommand, bindArgs, limit, offset, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Get all rows that match the given rowId.
   * This can be zero, one or more. It is more than one if there
   * is a sync conflict or if there are edit checkpoints.
   *
   * @param tableId      The table being updated
   * @param rowId        The rowId of the row being added.
   * @param callbackJSON The JSON object used by the JS layer to recover the callback function
   *                     that can process the response
   */
  public void getRows(String tableId, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("getRows: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_GET_ROWS, tableId,
        null, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Get the most recent checkpoint or last state for a row in the table.
   * Throws an exception if this row is in conflict.
   * Returns an empty rowset if the rowId is not present in the table.
   *
   * @param tableId      The table being updated
   * @param rowId        The rowId of the row being added.
   * @param callbackJSON The JSON object used by the JS layer to recover the callback function
   *                     that can process the response
   */
  public void getMostRecentRow(String tableId, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("getMostRecentRow: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(
        ExecutorRequestType.USER_TABLE_GET_MOST_RECENT_ROW, tableId, null, rowId, metaDataRev, callbackJSON,
        getFragmentID());

    queueRequest(request);
  }

  /**
   * Update a row in the table
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being changed.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   * @return see description in class header
   */
  public void updateRow(String tableId, String stringifiedJSON, String rowId,
                        String metaDataRev, String callbackJSON) {
    logDebug("updateRow: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_UPDATE_ROW,
        tableId, stringifiedJSON, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Update a row in the table with the given filter type and value.
   *
   * @param tableId
   * @param defaultAccess
   * @param owner
   * @param rowId
   * @param callbackJSON
    */
  public void changeAccessFilterOfRow(String tableId, String defaultAccess, String
      owner, String groupReadOnly, String groupModify, String groupPrivileged, String
      rowId, String metaDataRev, String callbackJSON) {

    logDebug("changeAccessFilter: " + tableId + " _id: " + rowId);
    HashMap<String,String> valueMap = new HashMap<String,String>();
    valueMap.put(DataTableColumns.DEFAULT_ACCESS, defaultAccess);
    valueMap.put(DataTableColumns.ROW_OWNER, owner);
    valueMap.put(DataTableColumns.GROUP_READ_ONLY, groupReadOnly);
    valueMap.put(DataTableColumns.GROUP_MODIFY, groupModify);
    valueMap.put(DataTableColumns.GROUP_PRIVILEGED, groupPrivileged);

    String stringifiedJSON = null;
    try {
      stringifiedJSON = ODKFileUtils.mapper.writeValueAsString(valueMap);
    } catch (JsonProcessingException e) {
      WebLogger.getLogger(mActivity.getAppName()).printStackTrace(e);
      return;
    }
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType
        .USER_TABLE_CHANGE_ACCESS_FILTER_ROW,
        tableId, stringifiedJSON, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);

  }
  /**
   * Delete a row from the table
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being deleted.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void deleteRow(String tableId, String stringifiedJSON, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("deleteRow: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_DELETE_ROW,
        tableId, stringifiedJSON, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Add a row in the table
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being added.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void addRow(String tableId, String stringifiedJSON, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("addRow: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_ADD_ROW, tableId,
        stringifiedJSON, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Update the row, marking the updates as a checkpoint save.
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being added.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void addCheckpoint(String tableId, String stringifiedJSON, String rowId,
                            String metaDataRev, String callbackJSON) {
    logDebug("addCheckpoint: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.USER_TABLE_ADD_CHECKPOINT,
        tableId, stringifiedJSON, rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Save checkpoint as incomplete. In the process, it applies any changes indicated by the stringifiedJSON.
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being saved-as-incomplete.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void saveCheckpointAsIncomplete(String tableId, String stringifiedJSON, String rowId,
                                         String metaDataRev, String callbackJSON) {
    logDebug("saveCheckpointAsIncomplete: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(
        ExecutorRequestType.USER_TABLE_SAVE_CHECKPOINT_AS_INCOMPLETE, tableId, stringifiedJSON,
        rowId, metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Save checkpoint as complete.
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON key-value map of values to store or update. If missing, the value remains unchanged.
   * @param rowId           The rowId of the row being marked-as-complete.
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void saveCheckpointAsComplete(String tableId, String stringifiedJSON, String rowId,
                                       String metaDataRev, String callbackJSON) {
    logDebug("saveCheckpointAsComplete: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(
        ExecutorRequestType.USER_TABLE_SAVE_CHECKPOINT_AS_COMPLETE, tableId, stringifiedJSON, rowId,
        metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Delete all checkpoint.  Checkpoints accumulate; this removes all of them.
   *
   * @param tableId      The table being updated
   * @param rowId        The rowId of the row being saved-as-incomplete.
   * @param callbackJSON The JSON object used by the JS layer to recover the callback function
   *                     that can process the response
   */
  public void deleteAllCheckpoints(String tableId, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("deleteAllCheckpoints: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(
        ExecutorRequestType.USER_TABLE_DELETE_ALL_CHECKPOINTS, tableId, null, rowId,
        metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Delete last checkpoint.  Checkpoints accumulate; this removes the most recent one, leaving earlier ones.
   *
   * @param tableId      The table being updated
   * @param rowId        The rowId of the row being saved-as-incomplete.
   * @param callbackJSON The JSON object used by the JS layer to recover the callback function
   *                     that can process the response
   */
  public void deleteLastCheckpoint(String tableId, String rowId, String metaDataRev, String callbackJSON) {
    logDebug("deleteLastCheckpoint: " + tableId + " _id: " + rowId);
    ExecutorRequest request = new ExecutorRequest(
        ExecutorRequestType.USER_TABLE_DELETE_LAST_CHECKPOINT, tableId, null, rowId,
        metaDataRev, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /****** LOCAL TABLE functions ******/
  /**
   * Create local table with columns
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON The key-value map of columns
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void createLocalOnlyTableWithColumns(String tableId, String stringifiedJSON,
                                              String callbackJSON) {

    logDebug("createLocalOnlyTableWithColumns: " + tableId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_CREATE_TABLE,
            tableId, stringifiedJSON, null, null, null, callbackJSON, getFragmentID());
    queueRequest(request);
  }

  /**
   * Delete local table
   *
   * @param tableId      The table being updated
   * @param callbackJSON The JSON object used by the JS layer to recover the callback function
   *                     that can process the response
   */
  public void deleteLocalOnlyTable(String tableId, String callbackJSON) {

    logDebug("deleteLocalOnlyTable: " + tableId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_DELETE_TABLE,
            tableId, null, null, null, null, callbackJSON, getFragmentID());
    queueRequest(request);
  }

  /**
   * Insert rows into local table
   *
   * @param tableId         The table being updated
   * @param stringifiedJSON The key-value map of columns
   * @param callbackJSON    The JSON object used by the JS layer to recover the callback function
   *                        that can process the response
   */
  public void insertLocalOnlyRow(String tableId, String stringifiedJSON, String callbackJSON) {

    logDebug("insertLocalOnlyRow: " + tableId);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_INSERT_ROW,
            tableId, stringifiedJSON, null, null, null, callbackJSON, getFragmentID());

      queueRequest(request);
  }

  /**
   * Update rows into local table
   *
   * @param tableId           The table being updated
   * @param stringifiedJSON   The key-value map of columns
   * @param whereClause       The where clause for to use when udpating rows
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values
   * @param callbackJSON      The JSON object used by the JS layer to recover the callback function
   *                          that can process the response
   */
  public void updateLocalOnlyRows(String tableId, String stringifiedJSON, String whereClause,
                                 String sqlBindParamsJSON, String callbackJSON) {

    logDebug("updateLocalOnlyRows: " + tableId + " whereClause: " + whereClause);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);
    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_UPDATE_ROW,
            tableId, stringifiedJSON, null, whereClause, bindArgs, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Delete rows in local table
   *
   * @param tableId           The table being updated
   * @param whereClause       The where clause for to use when udpating rows
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values
   * @param callbackJSON      The JSON object used by the JS layer to recover the callback function
   *                          that can process the response
   */
  public void deleteLocalOnlyRows(String tableId, String whereClause, String sqlBindParamsJSON,
                                  String callbackJSON) {

    logDebug("deleteLocalOnlyRows: " + tableId + " whereClause: " + whereClause);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);

    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_DELETE_ROW,
            tableId, null, null, whereClause, bindArgs, callbackJSON, getFragmentID());

    queueRequest(request);
  }

  /**
   * Simple query for local table
   *
   * @param tableId           The table being queried. This is a user-defined table.
   * @param whereClause       The where clause for the query
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values (including any in
   *                          the having clause)
   * @param groupBy           The array of columns to group by
   * @param having            The having clause
   * @param orderByElementKey The column to order by
   * @param orderByDirection  'ASC' or 'DESC' ordering
   * @param limit             The maximum number of rows to return (null returns all)
   * @param offset            The offset into the result set of the first row to return (null ok)
   * @param callbackJSON      The JSON object used by the JS layer to recover the callback function
   *                          that can process the response
   */
  public void simpleQueryLocalOnlyTables(String tableId, String whereClause, String sqlBindParamsJSON,
                                         String[] groupBy, String having, String orderByElementKey,
                                         String orderByDirection, Integer limit, Integer offset,
                                         String callbackJSON) {
    logDebug("simpleQueryLocalOnlyTables: " + tableId + " whereClause: " + whereClause);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);

    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_SIMPLE_QUERY,
            tableId, whereClause, bindArgs, groupBy, having,
            orderByElementKey, orderByDirection, limit, offset, false, null, callbackJSON,
            getFragmentID());

    queueRequest(request);
  }

  /**
   * Arbitrary query for local table
   *
   * @param tableId           The table being queried. If a result
   *                          column matches the column name in this tableId, then the data
   *                          type interpretations for that column will be applied to the result
   *                          column (e.g., integer, number, array, object conversions).
   * @param sqlCommand        The Select statement to issue. It can reference any table in the database,
   *                          including system tables.
   * @param sqlBindParamsJSON JSON.stringify of array of bind parameter values (including any in
   *                          the having clause)
   * @param limit             The maximum number of rows to return (null returns all)
   * @param offset            The offset into the result set of the first row to return (null ok)
   * @param callbackJSON      The JSON object used by the JS layer to recover the callback function
   *                          that can process the response
   */
  public void arbitrarySqlQueryLocalOnlyTables(String tableId, String sqlCommand, String sqlBindParamsJSON,
                                               Integer limit, Integer offset, String callbackJSON) {

    logDebug("arbitrarySqlQueryLocalOnlyTables: " + tableId + " sqlCommand: " + sqlCommand);
    BindArgs bindArgs = new BindArgs(sqlBindParamsJSON);

    ExecutorRequest request = new ExecutorRequest(ExecutorRequestType.LOCAL_TABLE_ARBITRARY_QUERY,
            tableId, sqlCommand, bindArgs, limit, offset, null, callbackJSON, getFragmentID());

    queueRequest(request);
  }

}
