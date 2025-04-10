// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager.BatchOpsInfo;
import io.github.muntashirakon.AppManager.batchops.struct.BatchAppOpsOptions;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.batchops.struct.BatchComponentOptions;
import io.github.muntashirakon.AppManager.batchops.struct.BatchPermissionOptions;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.progress.ProgressHandler;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

public class ProfileManager {
    public static final String TAG = "ProfileManager";

    public static final String PROFILE_EXT = ".am.json";

    @NonNull
    public static Path getProfilesDir() {
        Context context = ContextUtils.getContext();
        return Objects.requireNonNull(Paths.build(context.getFilesDir(), "profiles"));
    }

    @Nullable
    public static Path findProfilePathById(@NonNull String profileId) {
        return Paths.build(getProfilesDir(), profileId + PROFILE_EXT);
    }

    @NonNull
    public static Path requireProfilePathById(@NonNull String profileId) throws IOException {
        Path profilesDir = getProfilesDir();
        if (!profilesDir.exists()) {
            profilesDir.mkdirs();
        }
        return getProfilesDir().findOrCreateFile(profileId + PROFILE_EXT, null);
    }

    public static boolean deleteProfile(@NonNull String profileId) {
        Path profilePath = findProfilePathById(profileId);
        return profilePath == null || !profilePath.exists() || profilePath.delete();
    }

    @NonNull
    public static String getProfileName(@NonNull String filename) {
        int index = filename.indexOf(PROFILE_EXT);
        if (index == -1) {
            // Maybe only ends with .json
            index = filename.indexOf(".json");
        }
        return index != -1 ? filename.substring(0, index) : filename;
    }

    @NonNull
    public static ArrayList<String> getProfileNames() {
        Path profilesPath = getProfilesDir();
        String[] profilesFiles = profilesPath.listFileNames((dir, name) -> name.endsWith(PROFILE_EXT));
        ArrayList<String> profileNames = new ArrayList<>(profilesFiles.length);
        for (String profile : profilesFiles) {
            profileNames.add(getProfileName(profile));
        }
        return profileNames;
    }

    @NonNull
    public static HashMap<AppsProfile, CharSequence> getProfileSummaries(@NonNull Context context) throws IOException, JSONException {
        Path profilesPath = getProfilesDir();
        Path[] profilePaths = profilesPath.listFiles((dir, name) -> name.endsWith(PROFILE_EXT));
        HashMap<AppsProfile, CharSequence> profiles = new HashMap<>(profilePaths.length);
        for (Path profilePath : profilePaths) {
            if (ThreadUtils.isInterrupted()) {
                // Thread interrupted, return as is
                return profiles;
            }
            AppsProfile profile = AppsProfile.fromPath(profilePath);
            profiles.put(profile, profile.toLocalizedString(context));
        }
        return profiles;
    }

    @NonNull
    public static List<AppsProfile> getProfiles() throws IOException, JSONException {
        Path profilesPath = getProfilesDir();
        Path[] profilePaths = profilesPath.listFiles((dir, name) -> name.endsWith(PROFILE_EXT));
        List<AppsProfile> profiles = new ArrayList<>(profilePaths.length);
        for (Path profilePath : profilePaths) {
            profiles.add(AppsProfile.fromPath(profilePath));
        }
        return profiles;
    }

    @NonNull
    public static String getProfileIdCompat(@NonNull String profileName) {
        String profileId = Paths.sanitizeFilename(profileName, "_", Paths.SANITIZE_FLAG_SPACE
                | Paths.SANITIZE_FLAG_UNIX_ILLEGAL_CHARS | Paths.SANITIZE_FLAG_UNIX_RESERVED
                | Paths.SANITIZE_FLAG_FAT_ILLEGAL_CHARS);
        return profileId != null ? profileId : UUID.randomUUID().toString();
    }

    @NonNull
    private final AppsProfile mProfile;
    @Nullable
    private ProfileLogger mLogger;
    private boolean mRequiresRestart;

    public ProfileManager(@NonNull String profileId, @Nullable Path profilePath) throws IOException {
        try {
            mLogger = new ProfileLogger(profileId);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            Path realProfilePath = profilePath != null ? profilePath : findProfilePathById(profileId);
            mProfile = AppsProfile.fromPath(realProfilePath);
        } catch (IOException e) {
            if (mLogger != null) {
                mLogger.println(null, e);
            }
            throw e;
        } catch (JSONException e) {
            if (mLogger != null) {
                mLogger.println(null, e);
            }
            throw new IOException(e);
        }
    }

    public boolean requiresRestart() {
        return mRequiresRestart;
    }

    @SuppressLint("SwitchIntDef")
    public void applyProfile(@Nullable String state, @Nullable ProgressHandler progressHandler) {
        // Set state
        if (state == null) state = mProfile.state;

        log("====> Started execution with state " + state);

        if (mProfile.packages.length == 0) return;
        int[] users = mProfile.users == null ? Users.getUsersIds() : mProfile.users;
        int size = mProfile.packages.length * users.length;
        List<String> packages = new ArrayList<>(size);
        List<Integer> assocUsers = new ArrayList<>(size);
        for (String packageName : mProfile.packages) {
            for (int user : users) {
                packages.add(packageName);
                assocUsers.add(user);
            }
        }
        // Send progress
        if (progressHandler != null) {
            progressHandler.postUpdate(calculateMaxProgress(packages), 0);
        }
        BatchOpsManager batchOpsManager = new BatchOpsManager(mLogger);
        BatchOpsManager.Result result;
        // Apply component blocking
        String[] components = mProfile.components;
        if (components != null) {
            log("====> Started block/unblock components. State: " + state);
            BatchComponentOptions options = new BatchComponentOptions(components);
            int op;
            switch (state) {
                case AppsProfile.STATE_ON:
                    op = BatchOpsManager.OP_BLOCK_COMPONENTS;
                    break;
                case AppsProfile.STATE_OFF:
                    op = BatchOpsManager.OP_UNBLOCK_COMPONENTS;
                    break;
                default:
                    op = BatchOpsManager.OP_NONE;
            }
            BatchOpsInfo info = BatchOpsInfo.getInstance(op, packages, assocUsers, options);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped components.");
        // Apply app ops blocking
        int[] appOps = mProfile.appOps;
        if (appOps != null) {
            log("====> Started ignore/default components. State: " + state);
            int mode;
            switch (state) {
                case AppsProfile.STATE_ON:
                    mode = AppOpsManager.MODE_IGNORED;
                    break;
                case AppsProfile.STATE_OFF:
                default:
                    mode = AppOpsManager.MODE_DEFAULT;
            }
            BatchAppOpsOptions options = new BatchAppOpsOptions(appOps, mode);
            BatchOpsInfo info = BatchOpsInfo.getInstance(BatchOpsManager.OP_SET_APP_OPS, packages,
                    assocUsers, options);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped app ops.");
        // Apply permissions
        String[] permissions = mProfile.permissions;
        if (permissions != null) {
            log("====> Started grant/revoke permissions.");
            int op;
            switch (state) {
                case AppsProfile.STATE_ON:
                    op = BatchOpsManager.OP_REVOKE_PERMISSIONS;
                    break;
                case AppsProfile.STATE_OFF:
                    op = BatchOpsManager.OP_GRANT_PERMISSIONS;
                    break;
                default:
                    op = BatchOpsManager.OP_NONE;
            }
            BatchPermissionOptions options = new BatchPermissionOptions(permissions);
            BatchOpsInfo info = BatchOpsInfo.getInstance(op, packages, assocUsers, options);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped permissions.");
        // Backup rules
        Integer rulesFlag = mProfile.exportRules;
        if (rulesFlag != null) {
            log("====> Not implemented export rules.");
            // TODO(18/11/20): Export rules
        } else Log.d(TAG, "Skipped export rules.");
        // Disable/enable
        if (mProfile.freeze) {
            log("====> Started freeze/unfreeze. State: " + state);
            int op;
            switch (state) {
                case AppsProfile.STATE_ON:
                    op = BatchOpsManager.OP_FREEZE;
                    break;
                case AppsProfile.STATE_OFF:
                    op = BatchOpsManager.OP_UNFREEZE;
                    break;
                default:
                    op = BatchOpsManager.OP_NONE;
            }
            BatchOpsInfo info = BatchOpsInfo.getInstance(op, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped disable/enable.");
        // Force-stop
        if (mProfile.forceStop) {
            log("====> Started force-stop.");
            BatchOpsInfo info = BatchOpsInfo.getInstance(BatchOpsManager.OP_FORCE_STOP, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped force stop.");
        // Clear cache
        if (mProfile.clearCache) {
            log("====> Started clear cache.");
            BatchOpsInfo info = BatchOpsInfo.getInstance(BatchOpsManager.OP_CLEAR_CACHE, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped clear cache.");
        // Clear data
        if (mProfile.clearData) {
            log("====> Started clear data.");
            BatchOpsInfo info = BatchOpsInfo.getInstance(BatchOpsManager.OP_CLEAR_DATA, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped clear data.");
        // Block trackers
        if (mProfile.blockTrackers) {
            log("====> Started block trackers. State: " + state);
            int op;
            switch (state) {
                case AppsProfile.STATE_ON:
                    op = BatchOpsManager.OP_BLOCK_TRACKERS;
                    break;
                case AppsProfile.STATE_OFF:
                    op = BatchOpsManager.OP_UNBLOCK_TRACKERS;
                    break;
                default:
                    op = BatchOpsManager.OP_NONE;
            }
            BatchOpsInfo info = BatchOpsInfo.getInstance(op, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped block trackers.");
        // Backup apk
        if (mProfile.saveApk) {
            log("====> Started backup apk.");
            BatchOpsInfo info = BatchOpsInfo.getInstance(BatchOpsManager.OP_BACKUP_APK, packages, assocUsers, null);
            result = batchOpsManager.performOp(info, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped backup apk.");
        // Backup/restore data
        AppsProfile.BackupInfo backupInfo = mProfile.backupData;
        if (backupInfo != null) {
            log("====> Started backup/restore.");
            BackupFlags backupFlags = new BackupFlags(backupInfo.flags);
            String[] backupNames = null;
            if (backupFlags.backupMultiple() && backupInfo.name != null) {
                if (state.equals(AppsProfile.STATE_OFF)) {
                    backupNames = new String[]{UserHandleHidden.myUserId() + '_' + backupInfo.name};
                } else {
                    backupNames = new String[]{backupInfo.name};
                }
            }
            // Always add backup custom users
            backupFlags.addFlag(BackupFlags.BACKUP_CUSTOM_USERS);
            BatchBackupOptions options = new BatchBackupOptions(backupFlags.getFlags(), backupNames);
            int op;
            switch (state) {
                case AppsProfile.STATE_ON:  // Take backup
                    op = BatchOpsManager.OP_BACKUP;
                    break;
                case AppsProfile.STATE_OFF:  // Restore backup
                    op = BatchOpsManager.OP_RESTORE_BACKUP;
                    break;
                default:
                    op = BatchOpsManager.OP_NONE;
            }
            BatchOpsInfo info = BatchOpsInfo.getInstance(op, packages, assocUsers, options);
            result = batchOpsManager.performOp(info, progressHandler);
            mRequiresRestart |= result.requiresRestart();
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: %s", result);
            }
        } else Log.d(TAG, "Skipped backup/restore.");
        log("====> Execution completed.");
        batchOpsManager.conclude();
    }

    public void conclude() {
        if (mLogger != null) {
            mLogger.close();
        }
    }

    private int calculateMaxProgress(@NonNull List<String> userPackagePairs) {
        int packageCount = userPackagePairs.size();
        int opCount = 0;
        if (mProfile.components != null) ++opCount;
        if (mProfile.appOps != null) ++opCount;
        if (mProfile.permissions != null) ++opCount;
        // if (profile.exportRules != null) ++opCount; todo
        if (mProfile.freeze) ++opCount;
        if (mProfile.forceStop) ++opCount;
        if (mProfile.clearCache) ++opCount;
        if (mProfile.clearData) ++opCount;
        if (mProfile.blockTrackers) ++opCount;
        if (mProfile.saveApk) ++opCount;
        if (mProfile.backupData != null) ++opCount;
        return opCount * packageCount;
    }

    private void log(@Nullable String message) {
        if (mLogger != null) {
            mLogger.println(message);
        }
    }
}
