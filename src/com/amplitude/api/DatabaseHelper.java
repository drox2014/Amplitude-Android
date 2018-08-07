package com.amplitude.api;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class DatabaseHelper extends SQLiteOpenHelper {

    static final Map<String, DatabaseHelper> instances = new HashMap<String, DatabaseHelper>();

    private static final String TAG = "com.amplitude.api.DatabaseHelper";

    protected static final String STORE_TABLE_NAME = "store";
    protected static final String LONG_STORE_TABLE_NAME = "long_store";
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";

    protected static final String EVENT_TABLE_NAME = "events";
    protected static final String IDENTIFY_TABLE_NAME = "identifys";
    private static final String ID_FIELD = "id";
    private static final String EVENT_FIELD = "event";

    private static final String CREATE_STORE_TABLE = "CREATE TABLE IF NOT EXISTS "
            + STORE_TABLE_NAME + " (" + KEY_FIELD + " TEXT PRIMARY KEY NOT NULL, "
            + VALUE_FIELD + " TEXT);";
    private static final String CREATE_LONG_STORE_TABLE = "CREATE TABLE IF NOT EXISTS "
            + LONG_STORE_TABLE_NAME + " (" + KEY_FIELD + " TEXT PRIMARY KEY NOT NULL, "
            + VALUE_FIELD + " INTEGER);";
    private static final String CREATE_EVENTS_TABLE = "CREATE TABLE IF NOT EXISTS "
            + EVENT_TABLE_NAME + " (" + ID_FIELD + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + EVENT_FIELD + " TEXT);";
    private static final String CREATE_IDENTIFYS_TABLE = "CREATE TABLE IF NOT EXISTS "
            + IDENTIFY_TABLE_NAME + " (" + ID_FIELD + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + EVENT_FIELD + " TEXT);";

    private File file;
    private String instanceName;

    private static final AmplitudeLog logger = AmplitudeLog.getLogger();

    @Deprecated
    static DatabaseHelper getDatabaseHelper(Context context) {
        return getDatabaseHelper(context, null);
    }

    static synchronized DatabaseHelper getDatabaseHelper(Context context, String instance) {
        instance = Utils.normalizeInstanceName(instance);
        DatabaseHelper dbHelper = instances.get(instance);
        if (dbHelper == null) {
            dbHelper = new DatabaseHelper(context.getApplicationContext(), instance);
            instances.put(instance, dbHelper);
        }
        return dbHelper;
    }

    private static String getDatabaseName(String instance) {
        return (Utils.isEmptyString(instance) || instance.equals(Constants.DEFAULT_INSTANCE)) ? Constants.DATABASE_NAME : Constants.DATABASE_NAME + "_" + instance;
    }

    protected DatabaseHelper(Context context) {
        this(context, null);
    }

    protected DatabaseHelper(Context context, String instance) {
        super(context, getDatabaseName(instance), null, Constants.DATABASE_VERSION);
        file = context.getDatabasePath(getDatabaseName(instance));
        instanceName = Utils.normalizeInstanceName(instance);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_STORE_TABLE);
        db.execSQL(CREATE_LONG_STORE_TABLE);
        // INTEGER PRIMARY KEY AUTOINCREMENT guarantees that all generated values
        // for the field will be monotonically increasing and unique over the
        // lifetime of the table, even if rows get removed
        db.execSQL(CREATE_EVENTS_TABLE);
        db.execSQL(CREATE_IDENTIFYS_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion > newVersion) {
            logger.e(TAG, "onUpgrade() with invalid oldVersion and newVersion");
            resetDatabase(db);
            return;
        }

        if (newVersion <= 1) {
            return;
        }

        switch (oldVersion) {
            case 1:
                db.execSQL(CREATE_STORE_TABLE);
                if (newVersion <= 2) break;

            case 2:
                db.execSQL(CREATE_IDENTIFYS_TABLE);
                db.execSQL(CREATE_LONG_STORE_TABLE);
                if (newVersion <= 3) break;

            case 3:
                break;

            default:
                logger.e(TAG, "onUpgrade() with unknown oldVersion " + oldVersion);
                resetDatabase(db);
        }
    }

    private void resetDatabase(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + STORE_TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + LONG_STORE_TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + EVENT_TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + IDENTIFY_TABLE_NAME);
        onCreate(db);
    }

    synchronized long insertOrReplaceKeyValue(String key, String value) throws DatabaseResetException {
        return value == null ? deleteKeyFromTable(STORE_TABLE_NAME, key) :
            insertOrReplaceKeyValueToTable(STORE_TABLE_NAME, key, value);
    }

    synchronized long insertOrReplaceKeyLongValue(String key, Long value) throws DatabaseResetException {
        return value == null ? deleteKeyFromTable(LONG_STORE_TABLE_NAME, key) :
            insertOrReplaceKeyValueToTable(LONG_STORE_TABLE_NAME, key, value);
    }

    synchronized long insertOrReplaceKeyValueToTable(String table, String key, Object value) throws DatabaseResetException {
        long result = -1;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues contentValues = new ContentValues();
            contentValues.put(KEY_FIELD, key);
            if (value instanceof Long) {
                contentValues.put(VALUE_FIELD, (Long) value);
            } else {
                contentValues.put(VALUE_FIELD, (String) value);
            }
            result = db.insertWithOnConflict(
                    table,
                    null,
                    contentValues,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            if (result == -1) {
                logger.w(TAG, "Insert failed");
            }
        } catch (SQLiteException e) {
            String error = String.format("insertOrReplaceKeyValue in %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("insertOrReplaceKeyValue in %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            close();
        }
        return result;
    }

    synchronized long deleteKeyFromTable(String table, String key) throws DatabaseResetException {
        long result = -1;
        try {
            SQLiteDatabase db = getWritableDatabase();
            result = db.delete(table, KEY_FIELD + "=?", new String[]{key});
        } catch (SQLiteException e) {
            String error = String.format("deleteKey from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("deleteKey from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            close();
        }
        return result;
    }

    synchronized long addEvent(String event) throws DatabaseResetException {
        return addEventToTable(EVENT_TABLE_NAME, event);
    }

    synchronized long addIdentify(String identifyEvent) throws DatabaseResetException {
        return addEventToTable(IDENTIFY_TABLE_NAME, identifyEvent);
    }

    private synchronized long addEventToTable(String table, String event) throws DatabaseResetException {
        long result = -1;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues contentValues = new Co`ntentValues();
            contentValues.put(EVENT_FIELD, event);
            result = db.insert(table, null, contentValues);
            if (result == -1) {
                logger.w(TAG, String.format("Insert into %s failed", table));
            }
        } catch (SQLiteException e) {
            String error = String.format("addEvent to %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("addEvent to %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            close();
        }
        return result;
    }

    synchronized String getValue(String key) throws DatabaseResetException {
        return (String) getValueFromTable(STORE_TABLE_NAME, key);
    }

    synchronized Long getLongValue(String key) throws DatabaseResetException {
        return (Long) getValueFromTable(LONG_STORE_TABLE_NAME, key);
    }

    protected synchronized Object getValueFromTable(String table, String key) throws DatabaseResetException {
        Object value = null;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = queryDb(
                db, table, new String[]{KEY_FIELD, VALUE_FIELD}, KEY_FIELD + " = ?",
                new String[]{key}, null, null, null, null
            );
            if (cursor.moveToFirst()) {
                value = table.equals(STORE_TABLE_NAME) ? cursor.getString(1) : cursor.getLong(1);
            }
        } catch (SQLiteException e) {
            String error =  String.format("getValue from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error =  String.format("getValue from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } catch (RuntimeException e) {
            convertIfCursorWindowException(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            close();
        }
        return value;
    }

    synchronized List<JSONObject> getEvents(
                                        long upToId, long limit) throws JSONException, DatabaseResetException {
        return getEventsFromTable(EVENT_TABLE_NAME, upToId, limit);
    }

    synchronized List<JSONObject> getIdentifys(
                                        long upToId, long limit) throws JSONException, DatabaseResetException {
        return getEventsFromTable(IDENTIFY_TABLE_NAME, upToId, limit);
    }

    protected synchronized List<JSONObject> getEventsFromTable(
                                    String table, long upToId, long limit) throws JSONException, DatabaseResetException {
        List<JSONObject> events = new LinkedList<JSONObject>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = queryDb(
                db, table, new String[] { ID_FIELD, EVENT_FIELD },
                upToId >= 0 ? ID_FIELD + " <= " + upToId : null, null, null, null,
                ID_FIELD + " ASC", limit >= 0 ? "" + limit : null
            );

            while (cursor.moveToNext()) {
                long eventId = cursor.getLong(0);
                String event = cursor.getString(1);
                if (Utils.isEmptyString(event)) {
                    continue;
                }

                JSONObject obj = new JSONObject(event);
                obj.put("event_id", eventId);
                events.add(obj);
            }
        } catch (SQLiteException e) {
            String error = String.format("getEvents from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("getEvents from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } catch (RuntimeException e) {
            convertIfCursorWindowException(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            close();
        }
        return events;
    }

    synchronized long getEventCount() throws DatabaseResetException {
        return getEventCountFromTable(EVENT_TABLE_NAME);
    }

    synchronized long getIdentifyCount() throws DatabaseResetException {
        return getEventCountFromTable(IDENTIFY_TABLE_NAME);
    }

    synchronized long getTotalEventCount() throws DatabaseResetException {
        return getEventCount() + getIdentifyCount();
    }

    private synchronized long getEventCountFromTable(String table) throws DatabaseResetException {
        long numberRows = 0;
        SQLiteStatement statement = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            String query = "SELECT COUNT(*) FROM " + table;
            statement = db.compileStatement(query);
            numberRows = statement.simpleQueryForLong();
        } catch (SQLiteException e) {
            String error = String.format("getNumberRows for %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("getNumberRows for %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            if (statement != null) {
                statement.close();
            }
            close();
        }
        return numberRows;
    }

    synchronized long getNthEventId(long n) throws DatabaseResetException {
        return getNthEventIdFromTable(EVENT_TABLE_NAME, n);
    }

    synchronized long getNthIdentifyId(long n) throws DatabaseResetException {
        return getNthEventIdFromTable(IDENTIFY_TABLE_NAME, n);
    }

    private synchronized long getNthEventIdFromTable(String table, long n) throws DatabaseResetException {
        long nthEventId = -1;
        SQLiteStatement statement = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            String query = "SELECT " + ID_FIELD + " FROM " + table + " LIMIT 1 OFFSET "
                    + (n - 1);
            statement = db.compileStatement(query);
            nthEventId = -1;
            try {
                nthEventId = statement.simpleQueryForLong();
            } catch (SQLiteDoneException e) {
                logger.w(TAG, e);
            }
        } catch (SQLiteException e) {
            String error = String.format("getNthEventId from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("getNthEventId from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            if (statement != null) {
                statement.close();
            }
            close();
        }
        return nthEventId;
    }

    synchronized void removeEvents(long maxId) throws DatabaseResetException {
        removeEventsFromTable(EVENT_TABLE_NAME, maxId);
    }

    synchronized void removeIdentifys(long maxId) throws DatabaseResetException {
        removeEventsFromTable(IDENTIFY_TABLE_NAME, maxId);
    }

    private synchronized void removeEventsFromTable(String table, long maxId) throws DatabaseResetException {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(table, ID_FIELD + " <= " + maxId, null);
        } catch (SQLiteException e) {
            String error = String.format("removeEvents from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("removeEvents from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            close();
        }
    }

    synchronized void removeEvent(long id) throws DatabaseResetException {
        removeEventFromTable(EVENT_TABLE_NAME, id);
    }

    synchronized void removeIdentify(long id) throws DatabaseResetException {
        removeEventFromTable(IDENTIFY_TABLE_NAME, id);
    }

    private synchronized void removeEventFromTable(String table, long id) throws DatabaseResetException {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(table, ID_FIELD + " = " + id, null);
        } catch (SQLiteException e) {
            String error = String.format("removeEvent from %s failed", table);
            logger.e(TAG, error, e);
            // Hard to recover from SQLiteExceptions, just start fresh
            delete();
            throw new DatabaseResetException(error);
        } catch (StackOverflowError e) {
            String error = String.format("removeEvent from %s failed", table);
            logger.e(TAG, error, e);
            // potential stack overflow error when getting database on custom Android versions
            delete();
            throw new DatabaseResetException(error);
        } finally {
            close();
        }
    }

    private void delete() {
        try {
            close();
            file.delete();
        } catch (SecurityException e) {
            logger.e(TAG, "delete failed", e);
        }
    }

    boolean dbFileExists() {
        return file.exists();
    }

    // add level of indirection to facilitate mocking during unit tests
    Cursor queryDb(
        SQLiteDatabase db, String table, String[] columns, String selection,
        String[] selectionArgs, String groupBy, String having, String orderBy, String limit
    ) {
        return db.query(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit);
    }

    /*
        Checks if the RuntimeException is an android.database.CursorWindowAllocationException.
        If it is, then wrap the message in Amplitude's CursorWindowAllocationException so the
        AmplitudeClient can handle it. If not then rethrow.
     */
    private static void convertIfCursorWindowException(RuntimeException e) {
        String message = e.getMessage();
        if (!Utils.isEmptyString(message) && message.startsWith("Cursor window allocation of")) {
            throw new CursorWindowAllocationException(message);
        } else {
            throw e;
        }
    }
}
