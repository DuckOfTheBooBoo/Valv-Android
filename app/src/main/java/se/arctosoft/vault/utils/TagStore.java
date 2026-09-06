/*
 * Valv-Android
 * Copyright (C) 2024 Arctosoft AB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package se.arctosoft.vault.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import se.arctosoft.vault.data.GalleryFile;

/**
 * Stores per-video tags (short user labels shown as a caption, TikTok style).
 * <p>
 * Tags are kept in an {@link EncryptedSharedPreferences} file so the label text is
 * encrypted at rest (backed by the Android keystore) rather than stored as plaintext.
 * Each entry is keyed by the encrypted file's content URI.
 */
public class TagStore {
    private static final String TAG = "TagStore";
    private static final String PREFS_NAME = "video_tags";

    private static SharedPreferences prefs;

    private TagStore() {
    }

    @Nullable
    private static synchronized SharedPreferences getPrefs(@NonNull Context context) {
        if (prefs == null) {
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                prefs = EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKeyAlias,
                        context.getApplicationContext(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open encrypted tag store", e);
                return null;
            }
        }
        return prefs;
    }

    private static String keyFor(@NonNull GalleryFile galleryFile) {
        return galleryFile.getUri() != null ? galleryFile.getUri().toString() : galleryFile.getEncryptedName();
    }

    /**
     * Return the tag for the given file, loading it from disk once and caching it on the
     * {@link GalleryFile} for subsequent binds. Returns {@code null} when no tag is set.
     */
    @Nullable
    public static String getTag(@NonNull Context context, @NonNull GalleryFile galleryFile) {
        if (galleryFile.isTagLoaded()) {
            return galleryFile.getTag();
        }
        String tag = null;
        SharedPreferences p = getPrefs(context);
        if (p != null) {
            tag = p.getString(keyFor(galleryFile), null);
        }
        galleryFile.setTag(tag);
        return tag;
    }

    /**
     * Set (or, when {@code tag} is null/blank, remove) the tag for the given file.
     */
    public static void setTag(@NonNull Context context, @NonNull GalleryFile galleryFile, @Nullable String tag) {
        String trimmed = tag == null ? null : tag.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        SharedPreferences p = getPrefs(context);
        if (p != null) {
            SharedPreferences.Editor editor = p.edit();
            if (trimmed == null) {
                editor.remove(keyFor(galleryFile));
            } else {
                editor.putString(keyFor(galleryFile), trimmed);
            }
            editor.apply();
        }
        galleryFile.setTag(trimmed);
    }
}
