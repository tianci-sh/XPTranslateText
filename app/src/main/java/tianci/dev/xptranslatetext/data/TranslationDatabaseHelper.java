package tianci.dev.xptranslatetext.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;

/**
 * Tiny SQLite helper to cache translation results by a stable cache key.
 */
public class TranslationDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "xp_translation_text_cache.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_NAME = "translations";

    private static final String COL_CACHE_KEY = "cache_key";
    private static final String COL_TRANSLATED_TEXT = "translated_text";

    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                    + COL_CACHE_KEY + " TEXT PRIMARY KEY,"
                    + COL_TRANSLATED_TEXT + " TEXT"
                    + ")";

    public TranslationDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    /** Look up a translation by cache key, or null if absent. */
    public String getTranslation(String cacheKey) {
        SQLiteDatabase db = this.getReadableDatabase();
        String translation = null;
        Cursor cursor = null;
        try {
            cursor = db.query(
                    TABLE_NAME,
                    new String[]{COL_TRANSLATED_TEXT},
                    COL_CACHE_KEY + "=?",
                    new String[]{cacheKey},
                    null, null, null
            );
            if (cursor != null && cursor.moveToFirst()) {
                translation = cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return translation;
    }

    /** Insert or replace a translation for the given cache key. */
    public void putTranslation(String cacheKey, String translatedText) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CACHE_KEY, cacheKey);
        cv.put(COL_TRANSLATED_TEXT, translatedText);

        db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
