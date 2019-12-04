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

import android.content.ContentValues;

import androidx.annotation.NonNull;

import com.fasterxml.jackson.core.type.TypeReference;

import org.opendatakit.aggregate.odktables.rest.ElementDataType;
import org.opendatakit.aggregate.odktables.rest.KeyValueStoreConstants;
import org.opendatakit.aggregate.odktables.rest.entity.Column;
import org.opendatakit.database.data.BaseTable;
import org.opendatakit.database.data.ColumnDefinition;
import org.opendatakit.database.data.ColumnList;
import org.opendatakit.database.data.KeyValueStoreEntry;
import org.opendatakit.database.data.OrderedColumns;
import org.opendatakit.database.data.TableDefinitionEntry;
import org.opendatakit.database.data.TableMetaDataEntries;
import org.opendatakit.database.data.TypedRow;
import org.opendatakit.database.data.UserTable;
import org.opendatakit.database.queries.ResumableQuery;
import org.opendatakit.database.service.DbHandle;
import org.opendatakit.database.service.UserDbInterface;
import org.opendatakit.database.utilities.QueryUtil;
import org.opendatakit.exception.ActionNotAuthorizedException;
import org.opendatakit.exception.ServicesAvailabilityException;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.provider.DataTableColumns;
import org.opendatakit.utilities.ODKFileUtils;
import org.sqlite.database.sqlite.SQLiteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.opendatakit.utilities.ODKFileUtils.mapper;

/**
 * @author mitchellsundt@gmail.com
 */
public abstract class ExecutorProcessor implements Runnable {
   private static final String TAG = "ExecutorProcessor";

   // Changed this to protected so that extended
   // ExecutorProcessors can make use of this
   protected static final List<String> ADMIN_COLUMNS = DataTableColumns.getAdminColumns();
   protected static final String ID_COLUMN = "id";

   private ExecutorContext context;

   private ExecutorRequest request;
   private UserDbInterface dbInterface;
   private String transId;
   private DbHandle dbHandle;

   protected ExecutorProcessor(ExecutorContext context) {
      this.context = context;
   }

   @Override public void run() {
      this.request = context.peekRequest();
      if (request == null) {
         // no work to do...
         return;
      }

      dbInterface = context.getDatabase();
      if (dbInterface == null) {
         // no database to do the work...
         return;
      }

      try {
         // we have a request and a viable database interface...
         dbHandle = dbInterface.openDatabase(context.getAppName());
         if (dbHandle == null) {
            context.reportError(request.callbackJSON, request.callerID, null,
                IllegalStateException.class.getName() + ": Unable to open database connection");
            context.popRequest(true);
            return;
         }

         transId = UUID.randomUUID().toString();
         context.registerActiveConnection(transId, dbHandle);

         switch (request.executorRequestType) {
         case UPDATE_EXECUTOR_CONTEXT:
            updateExecutorContext();
            break;
         case GET_ROLES_LIST:
            getRolesList();
            break;
         case GET_DEFAULT_GROUP:
            getDefaultGroup();
            break;
         case GET_USERS_LIST:
            getUsersList();
            break;
         case GET_ALL_TABLE_IDS:
            getAllTableIds();
            break;
         case ARBITRARY_QUERY:
            arbitraryQuery();
            break;
         case USER_TABLE_QUERY:
            userTableQuery();
            break;
         case USER_TABLE_GET_ROWS:
            getRows();
            break;
         case USER_TABLE_GET_MOST_RECENT_ROW:
            getMostRecentRow();
            break;
         case USER_TABLE_UPDATE_ROW:
            updateRow();
            break;
         case USER_TABLE_CHANGE_ACCESS_FILTER_ROW:
            changeAccessFilterRow();
            break;
         case USER_TABLE_DELETE_ROW:
            deleteRow();
            break;
         case USER_TABLE_ADD_ROW:
            addRow();
            break;
         case USER_TABLE_ADD_CHECKPOINT:
            addCheckpoint();
            break;
         case USER_TABLE_SAVE_CHECKPOINT_AS_INCOMPLETE:
            saveCheckpointAsIncomplete();
            break;
         case USER_TABLE_SAVE_CHECKPOINT_AS_COMPLETE:
            saveCheckpointAsComplete();
            break;
         case USER_TABLE_DELETE_ALL_CHECKPOINTS:
            deleteAllCheckpoints();
            break;
         case USER_TABLE_DELETE_LAST_CHECKPOINT:
            deleteLastCheckpoint();
            break;
         case LOCAL_TABLE_CREATE_TABLE:
            createLocalOnlyTableWithColumns();
            break;
         case LOCAL_TABLE_DELETE_TABLE:
            deleteLocalOnlyTable();
            break;
         case LOCAL_TABLE_INSERT_ROW:
            insertLocalOnlyRow();
            break;
         case LOCAL_TABLE_UPDATE_ROW:
            updateLocalOnlyRows();
            break;
         case LOCAL_TABLE_DELETE_ROW:
            deleteLocalOnlyRows();
            break;
         case LOCAL_TABLE_SIMPLE_QUERY:
            simpleQueryLocalOnlyTables();
            break;
         case LOCAL_TABLE_ARBITRARY_QUERY:
            arbitrarySqlQueryLocalOnlyTables();
            break;
         default:
            reportErrorAndCleanUp(IllegalStateException.class.getName()
                + ": ExecutorProcessor has not implemented this request type!");
         }
      } catch (ActionNotAuthorizedException ex) {
         reportErrorAndCleanUp(
             ActionNotAuthorizedException.class.getName() + ": Not Authorized - " + ex
                 .getMessage());
      } catch (ServicesAvailabilityException e) {
         reportErrorAndCleanUp(
             ServicesAvailabilityException.class.getName() + ": " + e.getMessage());
      } catch (SQLiteException e) {
         reportErrorAndCleanUp(SQLiteException.class.getName() + ": " + e.getMessage());
      } catch (IllegalStateException e) {
         WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         reportErrorAndCleanUp(IllegalStateException.class.getName() + ": " + e.getMessage());
      } catch (Throwable t) {
         WebLogger.getLogger(context.getAppName()).printStackTrace(t);
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": ExecutorProcessor unexpected exception " + t
                 .toString());
      }
   }

   /**
    * Handle the open/close transaction treatment for the database and report an error.
    *
    * @param errorMessage
    */
   private void reportErrorAndCleanUp(String errorMessage) {
      try {
         if (dbHandle != null) {
            dbInterface.closeDatabase(context.getAppName(), dbHandle);
         }
      } catch (Throwable t) {
         // ignore this -- favor first reported error
         WebLogger.getLogger(context.getAppName()).printStackTrace(t);
         WebLogger.getLogger(context.getAppName())
             .w(TAG, "error while releasing database conneciton");
      } finally {
         context.removeActiveConnection(transId);
         context.reportError(request.callbackJSON, request.callerID, null, errorMessage);
         context.popRequest(true);
      }
   }

   /**
    * Handle the open/close transaction treatment for the database and report a success.
    *
    * @param data
    * @param metadata
    */
   private void reportSuccessAndCleanUp(ArrayList<List<Object>> data,
       Map<String, Object> metadata) {
      boolean successful = false;
      String exceptionString = null;
      try {
         dbInterface.closeDatabase(context.getAppName(), dbHandle);
         successful = true;
      } catch (ServicesAvailabilityException e) {
         exceptionString =
             e.getClass().getName() + ": error while closing database: " + e.toString();
         WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         WebLogger.getLogger(context.getAppName()).w(TAG, exceptionString);
      } catch (Throwable e) {
         String msg = e.getMessage();
         if (msg == null) {
            msg = e.toString();
         }
         exceptionString =
             IllegalStateException.class.getName() + ": unexpected exception " + e.getClass()
                 .getName() + " while closing database: " + msg;
         WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         WebLogger.getLogger(context.getAppName()).w(TAG, exceptionString);
      } finally {
         context.removeActiveConnection(transId);
         if (successful) {
            context.reportSuccess(request.callbackJSON, request.callerID, null, data, metadata);
         } else {
            context.reportError(request.callbackJSON, request.callerID, null, exceptionString);
         }
         context.popRequest(true);
      }
   }

   /**
    * Assumes incoming stringifiedJSON map only contains integers, doubles, strings, booleans
    * and arrays or string-value maps.
    *
    * @param columns
    * @param stringifiedJSON
    * @return ContentValues object drawn from stringifiedJSON
    */
   private ContentValues convertJSON(OrderedColumns columns, String stringifiedJSON) {
      ContentValues cvValues = new ContentValues();
      if (stringifiedJSON == null) {
         return cvValues;
      }
      try {
         TypeReference<HashMap<String, Object>> type = new TypeReference<HashMap<String, Object>>() {
         };
         HashMap<String, Object> map = ODKFileUtils.mapper.readValue(stringifiedJSON, type);
         // populate cvValues from the map...
         for (String key : map.keySet()) {
            // the only 3 metadata fields that the user should update are formId, locale, and creator
            // and administrators or super-users can modify the filter type and filter value
            if (!key.equals(DataTableColumns.FORM_ID) && !key.equals(DataTableColumns.LOCALE)
                && !key.equals(DataTableColumns.SAVEPOINT_CREATOR) && !key
                .equals(DataTableColumns.DEFAULT_ACCESS) && !key.equals(DataTableColumns.ROW_OWNER)
                && !key.equals(DataTableColumns.GROUP_READ_ONLY) && !key
                .equals(DataTableColumns.GROUP_MODIFY) && !key
                .equals(DataTableColumns.GROUP_PRIVILEGED)) {
               ColumnDefinition cd = columns.find(key);
               if (!cd.isUnitOfRetention()) {
                  throw new IllegalStateException("key is not a database column name: " + key);
               }
            }
            // the only types are integer/long, float/double, string, boolean
            // complex types (array, object) should come across the interface as strings
            Object value = map.get(key);
            if (value == null) {
               cvValues.putNull(key);
            } else if (value instanceof Long) {
               cvValues.put(key, (Long) value);
            } else if (value instanceof Integer) {
               cvValues.put(key, (Integer) value);
            } else if (value instanceof Float) {
               cvValues.put(key, (Float) value);
            } else if (value instanceof Double) {
               cvValues.put(key, (Double) value);
            } else if (value instanceof String) {
               cvValues.put(key, (String) value);
            } else if (value instanceof Boolean) {
               cvValues.put(key, (Boolean) value);
            } else {
               throw new IllegalStateException("unimplemented case");
            }
         }
         return cvValues;
      } catch (IOException e) {
         WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         throw new IllegalStateException("should never be reached");
      }
   }

   /**
    * Assumes incoming stringifiedJSON map only contains integers, doubles, strings, booleans
    * and arrays or string-value maps.
    *
    * @param stringifiedJSON
    * @return ContentValues object drawn from stringifiedJSON
    */
   private ContentValues convertLocalOnlyJSON(String stringifiedJSON) {
      ContentValues cvValues = new ContentValues();
      if (stringifiedJSON == null) {
         return cvValues;
      }
      try {
         TypeReference<HashMap<String, Object>> type = new TypeReference<HashMap<String, Object>>() {
         };
         HashMap<String, Object> map = mapper.readValue(stringifiedJSON, type);
         // populate cvValues from the map...
         for (String key : map.keySet()) {

            // the only types are integer/long, float/double, string, boolean
            // complex types (array, object) should come across the interface as strings
            Object value = map.get(key);
            if (value == null) {
               cvValues.putNull(key);
            } else if (value instanceof Long) {
               cvValues.put(key, (Long) value);
            } else if (value instanceof Integer) {
               cvValues.put(key, (Integer) value);
            } else if (value instanceof Float) {
               cvValues.put(key, (Float) value);
            } else if (value instanceof Double) {
               cvValues.put(key, (Double) value);
            } else if (value instanceof String) {
               cvValues.put(key, (String) value);
            } else if (value instanceof Boolean) {
               cvValues.put(key, (Boolean) value);
            } else {
               throw new IllegalStateException("convertLocalOnlyJSON: unimplemented case");
            }
         }
         return cvValues;
      } catch (IOException e) {
         WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         throw new IllegalStateException("convertLocalOnlyJSON: should never be reached");
      }
   }

   private void updateExecutorContext() {
      request.oldContext.releaseResources("switching to new WebFragment");
      context.popRequest(false);
   }

   private void getRolesList() throws ServicesAvailabilityException {
      String rolesList = dbInterface.getRolesList(context.getAppName());
      reportRolesListSuccessAndCleanUp(rolesList);
   }

   private void getDefaultGroup() throws ServicesAvailabilityException {
      String defaultGroup = dbInterface.getDefaultGroup(context.getAppName());
      reportDefaultGroupSuccessAndCleanUp(defaultGroup);
   }

   private void getUsersList() throws ServicesAvailabilityException {
      String usersList = dbInterface.getUsersList(context.getAppName());
      reportUsersListSuccessAndCleanUp(usersList);
   }

   private void getAllTableIds() throws ServicesAvailabilityException {
      List<String> tableIds = dbInterface.getAllTableIds(context.getAppName(), dbHandle);
      if (tableIds == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to obtain list of all tableIds");
      } else {
         reportListOfTableIdsSuccessAndCleanUp(tableIds);
      }
   }

   private void arbitraryQuery() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }
      BaseTable baseTable = dbInterface
          .arbitrarySqlQuery(context.getAppName(), dbHandle, request.tableId, request.sqlCommand,
              request.sqlBindParams, request.limit, request.offset);

      if (baseTable == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to rawQuery against: "
                 + request.tableId + " sql: " + request.sqlCommand);
      } else {
         reportArbitraryQuerySuccessAndCleanUp(columns, baseTable);
      }
   }

   private void populateKeyValueStoreList(Map<String, Object> metadata,
       List<KeyValueStoreEntry> entries) throws ServicesAvailabilityException {
      // keyValueStoreList
      if (entries != null) {
         Map<String, String> choiceMap = new HashMap<String, String>();
         // It is unclear how to most easily represent the KVS for access.
         // We use the convention in ODK Survey, which is a list of maps,
         // one per KVS row, with integer, number and boolean resolved to JS
         // types. objects and arrays are left unchanged.
         List<Map<String, Object>> kvsArray = new ArrayList<Map<String, Object>>();
         for (KeyValueStoreEntry entry : entries) {
            Object value = null;
            try {
               ElementDataType type = ElementDataType.valueOf(entry.type);
               if (entry.value != null) {
                  if (type == ElementDataType.integer) {
                     value = Long.parseLong(entry.value);
                  } else if (type == ElementDataType.bool) {
                     // This is broken - a value
                     // of "TRUE" is returned some times
                     value = entry.value;
                     if (value != null) {
                        try {
                           value = Boolean.valueOf(entry.value);
                        } catch (Exception e2) {
                           WebLogger.getLogger(context.getAppName()).e(TAG,
                               "ElementDataType: " + entry.type
                                   + " could not be converted from string");

                           try {
                              value = (Integer.parseInt(entry.value) != 0);
                           } catch (Exception e) {
                              WebLogger.getLogger(context.getAppName()).e(TAG,
                                  "ElementDataType: " + entry.type
                                      + " could not be converted from int");
                              e2.printStackTrace();
                           }
                        }
                     }
                  } else if (type == ElementDataType.number) {
                     value = Double.parseDouble(entry.value);
                  } else {
                     // string, array, object, rowpath, configpath and
                     // anything else -- do not attempt to convert.
                     // Leave as string.
                     value = entry.value;
                  }
               }
            } catch (IllegalArgumentException e) {
               // ignore?
               value = entry.value;
               WebLogger.getLogger(context.getAppName())
                   .e(TAG, "Unrecognized ElementDataType: " + entry.type);
               WebLogger.getLogger(context.getAppName()).printStackTrace(e);
            }

            Map<String, Object> anEntry = new HashMap<String, Object>();
            anEntry.put("partition", entry.partition);
            anEntry.put("aspect", entry.aspect);
            anEntry.put("key", entry.key);
            anEntry.put("type", entry.type);
            anEntry.put("value", value);

            kvsArray.add(anEntry);

            // and resolve the choice values
            // this may explode the size of the KVS going back to the JS layer.
            if (entry.partition.equals(KeyValueStoreConstants.PARTITION_COLUMN) && entry.key
                .equals(KeyValueStoreConstants.COLUMN_DISPLAY_CHOICES_LIST) && !choiceMap
                .containsKey(entry.value)) {
               String choiceList = dbInterface
                   .getChoiceList(context.getAppName(), dbHandle, entry.value);
               choiceMap.put(entry.value, choiceList);
            }
         }
         metadata.put("keyValueStoreList", kvsArray);
         metadata.put("choiceListMap", choiceMap);
      }
   }

   private void reportArbitraryQuerySuccessAndCleanUp(OrderedColumns columnDefinitions,
       BaseTable baseTable) throws ServicesAvailabilityException {

      ArrayList<List<Object>> data = new ArrayList<List<Object>>();

      TypeColumnWebIfCache typeColumnCache = new TypeColumnWebIfCache(columnDefinitions, baseTable);

      // assemble the data array
      for (int i = 0; i < baseTable.getNumberOfRows(); ++i) {
         TypedRow r = new TypedRow(baseTable.getRowAtIndex(i), columnDefinitions);
         Object[] values = new Object[baseTable.getWidth()];

         for (int idx = 0; idx < baseTable.getWidth(); ++idx) {
            values[idx] = typeColumnCache.getOdkDataWebIfDataByIndex(idx, r);
         }
         data.add(Arrays.asList(values));
      }

      Map<String, Object> metadata = getMetaData(columnDefinitions, baseTable);

      // raw queries are not extended.
      reportSuccessAndCleanUp(data, metadata);
   }

   private void reportRolesListSuccessAndCleanUp(String rolesList)
       throws ServicesAvailabilityException {

      ArrayList<String> roles = null;
      if (rolesList != null) {
         TypeReference<ArrayList<String>> type = new TypeReference<ArrayList<String>>() {
         };
         try {
            roles = ODKFileUtils.mapper.readValue(rolesList, type);
         } catch (IOException e) {
            WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         }
      }

      Map<String, Object> metadata = new HashMap<String, Object>();
      metadata.put("roles", roles);

      reportSuccessAndCleanUp(null, metadata);
   }

   private void reportDefaultGroupSuccessAndCleanUp(String defaultGroup)
       throws ServicesAvailabilityException {

      Map<String, Object> metadata = new HashMap<String, Object>();
      if (defaultGroup != null && defaultGroup.length() != 0) {
         metadata.put("defaultGroup", defaultGroup);
      }

      reportSuccessAndCleanUp(null, metadata);
   }

   private void reportUsersListSuccessAndCleanUp(String usersList)
       throws ServicesAvailabilityException {

      ArrayList<HashMap<String, Object>> users = null;
      if (usersList != null) {
         TypeReference<ArrayList<HashMap<String, Object>>> type = new TypeReference<ArrayList<HashMap<String, Object>>>() {
         };
         try {
            users = ODKFileUtils.mapper.readValue(usersList, type);
         } catch (IOException e) {
            WebLogger.getLogger(context.getAppName()).printStackTrace(e);
         }
      }

      Map<String, Object> metadata = new HashMap<String, Object>();
      metadata.put("users", users);

      reportSuccessAndCleanUp(null, metadata);
   }

   private void reportListOfTableIdsSuccessAndCleanUp(List<String> tableIds)
       throws ServicesAvailabilityException {

      Map<String, Object> metadata = new HashMap<String, Object>();
      metadata.put("tableIds", tableIds);

      // raw queries are not extended.
      reportSuccessAndCleanUp(null, metadata);
   }

   private void userTableQuery() throws ServicesAvailabilityException {
      String[] emptyArray = {};
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }
      UserTable t = dbInterface
          .simpleQuery(context.getAppName(), dbHandle, request.tableId, columns,
              request.whereClause, request.sqlBindParams, request.groupBy, request.having,
              QueryUtil.convertStringToArray(request.orderByElementKey),
              QueryUtil.convertStringToArray(request.orderByDirection), request.limit,
              request.offset);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to query " + request.tableId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void reportLocalOnlyTableQuerySuccessAndCleanUp(BaseTable baseTable,
       OrderedColumns orderedColumns) throws ServicesAvailabilityException {

      ArrayList<List<Object>> data = new ArrayList<List<Object>>();

      if (baseTable != null) {
         // assemble the data array
         for (int i = 0; i < baseTable.getNumberOfRows(); ++i) {
            TypedRow r = new TypedRow(baseTable.getRowAtIndex(i), orderedColumns);
            Object[] values = new Object[baseTable.getWidth()];

            for (int idx = 0; idx < baseTable.getWidth(); ++idx) {
               values[idx] = r.getOdkDataIfDataByIndex(idx);
            }
            data.add(Arrays.asList(values));
         }
      }

      Map<String, Object> metadata = new HashMap<String, Object>();
      ResumableQuery q = baseTable.getQuery();
      if (q != null) {
         metadata.put("limit", q.getSqlLimit());
         metadata.put("offset", q.getSqlOffset());
      }

      metadata.put("tableId", orderedColumns.getTableId());

      // elementKey -> index in row within row list
      metadata.put("elementKeyMap", baseTable.getElementKeyToIndex());

      // TODO: add dataTableModel to metadata once type information is available
      // dataTableModel -- JS nested schema struct { elementName : extended_JS_schema_struct, ...}
      //TreeMap<String, Object> dataTableModel = orderedColumns.getDataModel();
      //metadata.put("dataTableModel", dataTableModel);

      // raw queries are not extended.
      reportSuccessAndCleanUp(data, metadata);
   }

   private void reportSuccessAndCleanUp(UserTable userTable) throws ServicesAvailabilityException {
      TableMetaDataEntries metaDataEntries = dbInterface
          .getTableMetadata(context.getAppName(), dbHandle, request.tableId, null, null, null,
              null);

      TableDefinitionEntry tdef = dbInterface
          .getTableDefinitionEntry(context.getAppName(), dbHandle, request.tableId);

      // assemble the data and metadata objects
      ArrayList<List<Object>> data = new ArrayList<List<Object>>();

      TypeColumnWebIfCache typeColumnCache = new TypeColumnWebIfCache(userTable.getColumnDefinitions(),
          userTable.getBaseTable());

      for (int i = 0; i < userTable.getNumberOfRows(); ++i) {
         TypedRow r = userTable.getRowAtIndex(i);
         Object[] typedValues = new Object[userTable.getWidth()];
         for (int idx = 0; idx < userTable.getWidth(); ++idx) {
            typedValues[idx] = typeColumnCache.getOdkDataWebIfDataByIndex(idx,r);
         }
         List<Object> typedValuesAsList = Arrays.asList(typedValues);
         data.add(typedValuesAsList);
      }

      Map<String, Object> metadata = getMetaDataForUserTable(userTable);

      reportSuccessAndCleanUp(data, metadata);
   }

   protected abstract void extendQueryMetadata(UserDbInterface dbInterface, DbHandle dbHandle,
       List<KeyValueStoreEntry> entries, UserTable userTable, Map<String, Object> metadata);

   private void getRows() throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }
      UserTable t = dbInterface
          .getRowsWithId(context.getAppName(), dbHandle, request.tableId, columns, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to getRows for " + request.tableId
                 + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void getMostRecentRow()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }
      UserTable t = dbInterface
          .getMostRecentRowWithId(context.getAppName(), dbHandle, request.tableId, columns,
              request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to getMostRecentRow for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void updateRow() throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
      UserTable t = dbInterface
          .updateRowWithId(context.getAppName(), dbHandle, request.tableId, columns, cvValues,
              request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to updateRow for " + request.tableId
                 + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void changeAccessFilterRow()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
      String defaultAccess = cvValues.getAsString(DataTableColumns.DEFAULT_ACCESS);
      String owner = cvValues.getAsString(DataTableColumns.ROW_OWNER);
      String groupReadOnly = cvValues.getAsString(DataTableColumns.GROUP_READ_ONLY);
      String groupModify = cvValues.getAsString(DataTableColumns.GROUP_MODIFY);
      String groupPrivileged = cvValues.getAsString(DataTableColumns.GROUP_PRIVILEGED);

      UserTable t = dbInterface
          .changeRowFilterWithId(context.getAppName(), dbHandle, request.tableId, columns,
              defaultAccess, owner, groupReadOnly, groupModify, groupPrivileged, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to changeAccessFilterRow for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void deleteRow() throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      UserTable t = dbInterface
          .deleteRowWithId(context.getAppName(), dbHandle, request.tableId, columns, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to deleteRow for " + request.tableId
                 + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void addRow() throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
      UserTable t = dbInterface
          .insertRowWithId(context.getAppName(), dbHandle, request.tableId, columns, cvValues,
              request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to addRow for " + request.tableId
                 + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void addCheckpoint() throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
      UserTable t = dbInterface
          .insertCheckpointRowWithId(context.getAppName(), dbHandle, request.tableId, columns,
              cvValues, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to addCheckpoint for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void saveCheckpointAsIncomplete()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      if (request.stringifiedJSON != null) {
         ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
         if (!cvValues.keySet().isEmpty()) {
            dbInterface
                .insertCheckpointRowWithId(context.getAppName(), dbHandle, request.tableId, columns,
                    cvValues, request.rowId);
         }
      }

      UserTable t = dbInterface
          .saveAsIncompleteMostRecentCheckpointRowWithId(context.getAppName(), dbHandle,
              request.tableId, columns, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to saveCheckpointAsIncomplete for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void saveCheckpointAsComplete()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }
      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      if (request.stringifiedJSON != null) {
         ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
         if (!cvValues.keySet().isEmpty()) {
            dbInterface
                .insertCheckpointRowWithId(context.getAppName(), dbHandle, request.tableId, columns,
                    cvValues, request.rowId);
         }
      }

      UserTable t = dbInterface
          .saveAsCompleteMostRecentCheckpointRowWithId(context.getAppName(), dbHandle,
              request.tableId, columns, request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to saveCheckpointAsComplete for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void deleteLastCheckpoint()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }

      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      UserTable t = dbInterface
          .deleteLastCheckpointRowWithId(context.getAppName(), dbHandle, request.tableId, columns,
              request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to deleteLastCheckpoint for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void deleteAllCheckpoints()
       throws ServicesAvailabilityException, ActionNotAuthorizedException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }
      if (request.rowId == null) {
         reportErrorAndCleanUp(IllegalArgumentException.class.getName() + ": rowId cannot be null");
         return;
      }

      OrderedColumns columns = context.getOrderedColumns(request.tableId);
      if (columns == null) {
         columns = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, columns);
      }

      //ContentValues cvValues = convertJSON(columns, request.stringifiedJSON);
      UserTable t = dbInterface
          .deleteAllCheckpointRowsWithId(context.getAppName(), dbHandle, request.tableId, columns,
              request.rowId);

      if (t == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to deleteAllCheckpoints for "
                 + request.tableId + "._id = " + request.rowId);
      } else {
         reportSuccessAndCleanUp(t);
      }
   }

   private void createLocalOnlyTableWithColumns()
       throws ServicesAvailabilityException, IOException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      if (request.stringifiedJSON == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": stringifiedJSON cannot be null");
         return;
      }

      ArrayList<Column> colArrayList = ODKFileUtils.mapper.readValue(request.stringifiedJSON,
          ODKFileUtils.mapper.getTypeFactory()
              .constructCollectionType(ArrayList.class, Column.class));
      ColumnList colList = new ColumnList(colArrayList);

      OrderedColumns localCols = dbInterface
          .createLocalOnlyTableWithColumns(context.getAppName(), dbHandle, request.tableId,
              colList);

      if (localCols == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable create table: " + request.tableId);
      } else {
         // TODO: Pass localCols back once type is respected
         reportSuccessAndCleanUp(null, null);
      }
   }

   private void deleteLocalOnlyTable() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      dbInterface.deleteLocalOnlyTable(context.getAppName(), dbHandle, request.tableId);

      reportSuccessAndCleanUp(null, null);
   }

   private void insertLocalOnlyRow() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      if (request.stringifiedJSON == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": stringifiedJSON cannot be null");
         return;
      }

      ContentValues cvValues = convertLocalOnlyJSON(request.stringifiedJSON);
      dbInterface.insertLocalOnlyRow(context.getAppName(), dbHandle, request.tableId, cvValues);

      reportSuccessAndCleanUp(null, null);
   }

   private void updateLocalOnlyRows() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      if (request.stringifiedJSON == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": stringifiedJSON cannot be null");
         return;
      }

      ContentValues cvValues = convertLocalOnlyJSON(request.stringifiedJSON);

      dbInterface.updateLocalOnlyRows(context.getAppName(), dbHandle, request.tableId, cvValues,
          request.whereClause, request.sqlBindParams);

      reportSuccessAndCleanUp(null, null);
   }

   private void deleteLocalOnlyRows() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      dbInterface
          .deleteLocalOnlyRows(context.getAppName(), dbHandle, request.tableId, request.whereClause,
              request.sqlBindParams);

      reportSuccessAndCleanUp(null, null);
   }

   private void simpleQueryLocalOnlyTables() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      // TODO: Get type information
      // OrderedColumns for unsynced tables are not currently stored in the _column_definitions
      // If they were, the code below would retrieve type information
      OrderedColumns ordCols = context.getOrderedColumns(request.tableId);
      if (ordCols == null) {
         ordCols = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, ordCols);
      }

      BaseTable baseTable = dbInterface
          .simpleQueryLocalOnlyTables(context.getAppName(), dbHandle, request.tableId,
              request.whereClause, request.sqlBindParams, request.groupBy, request.having,
              QueryUtil.convertStringToArray(request.orderByElementKey),
              QueryUtil.convertStringToArray(request.orderByDirection), request.limit,
              request.offset);

      if (baseTable == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to query " + request.tableId);
      } else {
         reportLocalOnlyTableQuerySuccessAndCleanUp(baseTable, ordCols);
      }

   }

   private void arbitrarySqlQueryLocalOnlyTables() throws ServicesAvailabilityException {
      if (request.tableId == null) {
         reportErrorAndCleanUp(
             IllegalArgumentException.class.getName() + ": tableId cannot be null");
         return;
      }

      // TODO: Get type information
      // OrderedColumns for unsynced tables are not currently stored in the _column_definitions
      // If they were, the code below would retrieve type information
      OrderedColumns ordCols = context.getOrderedColumns(request.tableId);
      if (ordCols == null) {
         ordCols = dbInterface
             .getUserDefinedColumns(context.getAppName(), dbHandle, request.tableId);
         context.putOrderedColumns(request.tableId, ordCols);
      }

      BaseTable baseTable = dbInterface
          .arbitrarySqlQueryLocalOnlyTables(context.getAppName(), dbHandle, request.tableId,
              request.sqlCommand, request.sqlBindParams, request.limit, request.offset);

      if (baseTable == null) {
         reportErrorAndCleanUp(
             IllegalStateException.class.getName() + ": Unable to rawQuery against: "
                 + request.tableId + " sql: " + request.sqlCommand);
      } else {
         reportLocalOnlyTableQuerySuccessAndCleanUp(baseTable, ordCols);
      }
   }

   @NonNull private Map<String, Object> getMetaData(OrderedColumns columnDefinitions,
       BaseTable baseTable) throws ServicesAvailabilityException {
      TableMetaDataEntries metaDataEntries = dbInterface
          .getTableMetadata(context.getAppName(), dbHandle, request.tableId, null, null, null,
              null);
      TableDefinitionEntry tdef = dbInterface
          .getTableDefinitionEntry(context.getAppName(), dbHandle, request.tableId);

      return getMetaData(metaDataEntries, tdef, columnDefinitions, baseTable);
   }

   @NonNull private Map<String, Object> getMetaDataForUserTable(UserTable userTable)
       throws ServicesAvailabilityException {

      TableMetaDataEntries metaDataEntries = dbInterface
          .getTableMetadata(context.getAppName(), dbHandle, request.tableId, null, null, null,
              null);
      TableDefinitionEntry tdef = dbInterface
          .getTableDefinitionEntry(context.getAppName(), dbHandle, request.tableId);

      Map<String, Object> metadata = getMetaData(metaDataEntries, tdef,
          userTable.getColumnDefinitions(), userTable.getBaseTable());

      // Always include tool-specific metadata if we are including metadata.
      // The tool-specific metadata, such as cell, row, status and column colors
      // varies with the content of the individual rows and therefore must always
      // be returned.
      if (request.includeFullMetadata) {
         // extend the metadata with whatever else this app needs....
         // e.g., row and column color maps
         extendQueryMetadata(dbInterface, dbHandle, metaDataEntries.getEntries(), userTable,
             metadata);
      }

      return metadata;
   }

   @NonNull private Map<String, Object> getMetaData(TableMetaDataEntries metaDataEntries,
       TableDefinitionEntry tdef, OrderedColumns columnDefinitions, BaseTable baseTable)
       throws ServicesAvailabilityException {

      Map<String, Object> metadata = new HashMap<String, Object>();
      ResumableQuery q = baseTable.getQuery();
      if (q != null) {
         metadata.put("limit", q.getSqlLimit());
         metadata.put("offset", q.getSqlOffset());
      }
      metadata.put("canCreateRow", baseTable.getEffectiveAccessCreateRow());
      metadata.put("tableId", columnDefinitions.getTableId());
      metadata.put("schemaETag", tdef.getSchemaETag());
      metadata.put("lastDataETag", tdef.getLastDataETag());
      metadata.put("lastSyncTime", tdef.getLastSyncTime());

      // elementKey -> index in row within row list
      metadata.put("elementKeyMap", baseTable.getElementKeyToIndex());

      // include metadata only if requested and if the existing metadata version is out-of-date.
      if (request.tableId != null && request.includeFullMetadata && (request.metaDataRev == null
          || !request.metaDataRev.equals(metaDataEntries.getRevId()))) {

         Map<String, Object> cachedMetadata = new HashMap<String, Object>();

         cachedMetadata.put("metaDataRev", metaDataEntries.getRevId());
         // dataTableModel -- JS nested schema struct { elementName : extended_JS_schema_struct, ...}
         TreeMap<String, Object> dataTableModel = columnDefinitions.getExtendedDataModel();
         cachedMetadata.put("dataTableModel", dataTableModel);
         // keyValueStoreList
         populateKeyValueStoreList(cachedMetadata, metaDataEntries.getEntries());
         metadata.put("cachedMetadata", cachedMetadata);
      }
      return metadata;
   }

}
