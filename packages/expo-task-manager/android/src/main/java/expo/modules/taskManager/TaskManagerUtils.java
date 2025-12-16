package expo.modules.taskManager;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import expo.modules.interfaces.taskManager.TaskExecutionCallback;
import expo.modules.interfaces.taskManager.TaskInterface;
import expo.modules.interfaces.taskManager.TaskManagerUtilsInterface;

public class TaskManagerUtils implements TaskManagerUtilsInterface {

  // Key that every job created by the task manager must contain in its extras
  // bundle.
  private static final String EXTRAS_REQUIRED_KEY = "expo.modules.taskManager";
  private static final String TAG = "TaskManagerUtils";

  // Request code number used for pending intents created by this module.
  private static final int PENDING_INTENT_REQUEST_CODE = 5055;

  private static final int DEFAULT_OVERRIDE_DEADLINE = 60 * 1000; // 1 minute

  // Android limits apps to 100 total scheduled jobs.
  private static final int ANDROID_JOB_LIMIT = 100;

  // Reserve some job slots for safety margin (race conditions, other components).
  private static final int JOB_LIMIT_SAFETY_BUFFER = 10;

  // Minimum jobs per task to ensure functionality even with many tasks.
  private static final int MIN_JOBS_PER_TASK = 3;

  // Merge window thresholds (in milliseconds) based on queue pressure.
  // Lower pressure = shorter window = more separate jobs = faster execution.
  // Higher pressure = longer window = more merging = stay under limits.
  private static final long MERGE_WINDOW_LOW_PRESSURE_MS = 1_000; // 1 second
  private static final long MERGE_WINDOW_MEDIUM_PRESSURE_MS = 10_000; // 10 seconds
  private static final long MERGE_WINDOW_HIGH_PRESSURE_MS = 60_000; // 1 minute

  // region TaskManagerUtilsInterface

  @Override
  public PendingIntent createTaskIntent(Context context, TaskInterface task) {
    return createTaskIntent(context, task, PendingIntent.FLAG_UPDATE_CURRENT);
  }

  @Override
  public void cancelTaskIntent(Context context, String appScopeKey, String taskName) {
    PendingIntent pendingIntent = createTaskIntent(context, appScopeKey, taskName, PendingIntent.FLAG_NO_CREATE);

    if (pendingIntent != null) {
      pendingIntent.cancel();
    }
  }

  @Override
  public void scheduleJob(Context context, @NonNull TaskInterface task, List<PersistableBundle> data) {
    if (task == null) {
      Log.e(TAG, "Trying to schedule job for null task!");
    } else {
      updateOrScheduleJob(context, task, data);
    }
  }

  @Override
  public void executeTask(TaskInterface task, Bundle data, @Nullable TaskExecutionCallback callback) {
    if (task == null) {
      Log.e(TAG, "Trying to execute a null task!");
    } else {
      task.execute(data, null, callback);
    }
  }

  @Override
  public void cancelScheduledJob(Context context, int jobId) {
    JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

    if (jobScheduler != null) {
      jobScheduler.cancel(jobId);
    } else {
      Log.e(this.getClass().getName(), "Job scheduler not found!");
    }
  }

  @Override
  public List<PersistableBundle> extractDataFromJobParams(JobParameters params) {
    PersistableBundle extras = params.getExtras();
    List<PersistableBundle> data = new ArrayList<>();
    int dataSize = extras.getInt("dataSize", 0);

    for (int i = 0; i < dataSize; i++) {
      data.add(extras.getPersistableBundle(String.valueOf(i)));
    }
    return data;
  }

  // endregion TaskManagerUtilsInterface
  // region private helpers

  /**
   * Calculate the merge window based on queue pressure.
   * Lower pressure = shorter window = prefer creating new jobs.
   * Higher pressure = longer window = prefer merging into existing jobs.
   */
  private long getMergeWindowMs(int pendingJobsForTask, int maxJobsPerTask) {
    if (maxJobsPerTask <= 0) {
      return Long.MAX_VALUE;
    }

    float pressure = (float) pendingJobsForTask / maxJobsPerTask;

    if (pressure < 0.1f) {
      return MERGE_WINDOW_LOW_PRESSURE_MS; // Almost empty - prefer new jobs
    } else if (pressure < 0.5f) {
      return MERGE_WINDOW_MEDIUM_PRESSURE_MS; // Filling up - merge if recent
    } else if (pressure < 0.8f) {
      return MERGE_WINDOW_HIGH_PRESSURE_MS; // Getting full - merge more aggressively
    } else {
      return Long.MAX_VALUE; // Critical - merge into any non-executing job
    }
  }

  private void updateOrScheduleJob(Context context, TaskInterface task, List<PersistableBundle> data) {
    JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

    if (jobScheduler == null) {
      Log.e(this.getClass().getName(), "Job scheduler not found!");
      return;
    }

    List<JobInfo> pendingJobs = jobScheduler.getAllPendingJobs();
    if (pendingJobs == null) {
      // There is a mismatch between documentation and implementation. In the
      // reference implementation null is returned in case of RemoteException:
      // https://android.googlesource.com/platform//frameworks/base/+/980636f0a5440a12f5d8896d8738c6fcf2430553/apex/jobscheduler/framework/java/android/app/JobSchedulerImpl.java#137
      pendingJobs = new ArrayList<>();
    }

    // Sort by job ID (ascending) to find lowest unused ID
    Collections.sort(pendingJobs, new Comparator<JobInfo>() {
      @Override
      public int compare(JobInfo a, JobInfo b) {
        return Integer.compare(a.getId(), b.getId());
      }
    });

    // Collect info about pending jobs for this task
    int newJobId = 0;
    int pendingJobsForTask = 0;
    Set<String> uniqueTaskNames = new HashSet<>();
    List<JobInfo> taskJobs = new ArrayList<>();

    for (JobInfo jobInfo : pendingJobs) {
      int jobId = jobInfo.getId();
      PersistableBundle extras = jobInfo.getExtras();

      // Track unique task names for dynamic threshold calculation
      if (extras != null && extras.containsKey(EXTRAS_REQUIRED_KEY)) {
        String jobTaskName = extras.getString("taskName");
        if (jobTaskName != null) {
          uniqueTaskNames.add(jobTaskName);
        }
      }

      if (isJobInfoRelatedToTask(jobInfo, task)) {
        pendingJobsForTask++;
        taskJobs.add(jobInfo);

        // Skip this job ID, continue looking for an unused ID
        if (newJobId == jobId) {
          newJobId++;
        }
        continue;
      }
      if (newJobId == jobId) {
        newJobId++;
      }
    }

    // Include current task in count if not already present
    uniqueTaskNames.add(task.getName());

    // Calculate dynamic per-task job limit based on number of unique tasks.
    // This ensures fair distribution of Android's 100 job limit across all tasks.
    int availableSlots = ANDROID_JOB_LIMIT - JOB_LIMIT_SAFETY_BUFFER;
    int maxJobsPerTask = Math.max(MIN_JOBS_PER_TASK, availableSlots / uniqueTaskNames.size());

    // Calculate merge window based on queue pressure
    long mergeWindowMs = getMergeWindowMs(pendingJobsForTask, maxJobsPerTask);
    long now = System.currentTimeMillis();

    // Sort task jobs by scheduled time (newest first) for merge candidate selection
    Collections.sort(taskJobs, new Comparator<JobInfo>() {
      @Override
      public int compare(JobInfo a, JobInfo b) {
        long timeA = a.getExtras().getLong("scheduledTime", 0);
        long timeB = b.getExtras().getLong("scheduledTime", 0);
        return Long.compare(timeB, timeA); // Descending (newest first)
      }
    });

    // Try to find a job we can safely merge into:
    // - Not currently executing (race condition protection)
    // - Young enough (within merge window based on pressure)
    for (JobInfo jobInfo : taskJobs) {
      int jobId = jobInfo.getId();
      PersistableBundle extras = jobInfo.getExtras();
      long scheduledTime = extras.getLong("scheduledTime", 0);
      long jobAgeMs = now - scheduledTime;

      // Skip jobs that are currently executing (race condition protection)
      if (TaskJobService.isJobExecuting(jobId)) {
        Log.d(TAG, "Job " + jobId + " is executing, skipping for merge.");
        continue;
      }

      // Check if job is young enough to merge into
      if (jobAgeMs < mergeWindowMs) {
        // Safe to merge - job is young and not executing
        Log.i(TAG, "Merging data into job " + jobId + " for task '" + task.getName() +
            "' (age=" + jobAgeMs + "ms, window=" + mergeWindowMs + "ms, pressure=" +
            pendingJobsForTask + "/" + maxJobsPerTask + ")");
        try {
          JobInfo mergedJobInfo = createJobInfoByMergingData(jobInfo, data);
          jobScheduler.cancel(jobId);
          jobScheduler.schedule(mergedJobInfo);
        } catch (IllegalStateException e) {
          Log.e(this.getClass().getName(), "Unable to merge into existing job: " + e.getMessage());
        }
        return;
      }
    }

    // No mergeable job found - check if we can create a new one
    if (pendingJobsForTask < maxJobsPerTask) {
      // Under limit - create new job
      if (pendingJobsForTask > 0) {
        Log.i(TAG, "Creating new job " + newJobId + " for task '" + task.getName() +
            "' (" + pendingJobsForTask + " existing jobs too old to merge)");
      }
      try {
        JobInfo jobInfo = createJobInfo(context, task, newJobId, data);
        jobScheduler.schedule(jobInfo);
      } catch (IllegalStateException e) {
        Log.e(this.getClass().getName(), "Unable to schedule a new job: " + e.getMessage());
      }
      return;
    }

    // At limit with no young jobs - force merge into newest non-executing job
    for (JobInfo jobInfo : taskJobs) {
      int jobId = jobInfo.getId();
      if (!TaskJobService.isJobExecuting(jobId)) {
        Log.w(TAG, "At job limit for task '" + task.getName() + "' (" + pendingJobsForTask +
            "/" + maxJobsPerTask + "). Force merging into job " + jobId);
        try {
          JobInfo mergedJobInfo = createJobInfoByMergingData(jobInfo, data);
          jobScheduler.cancel(jobId);
          jobScheduler.schedule(mergedJobInfo);
        } catch (IllegalStateException e) {
          Log.e(this.getClass().getName(), "Unable to force merge into job: " + e.getMessage());
        }
        return;
      }
    }

    // All jobs are executing - this is extremely rare, just log and drop
    Log.e(TAG, "All " + pendingJobsForTask + " jobs for task '" + task.getName() +
        "' are executing. Cannot schedule or merge. Data dropped.");
  }

  private JobInfo createJobInfoByMergingData(JobInfo jobInfo, List<PersistableBundle> data) {
    PersistableBundle mergedExtras = jobInfo.getExtras();
    int existingDataSize = mergedExtras.getInt("dataSize", 0);

    if (data != null) {
      mergedExtras.putInt("dataSize", existingDataSize + data.size());

      for (int i = 0; i < data.size(); i++) {
        mergedExtras.putPersistableBundle(String.valueOf(existingDataSize + i), data.get(i));
      }
    }

    // Update scheduled time since we're rescheduling
    mergedExtras.putLong("scheduledTime", System.currentTimeMillis());

    return createJobInfo(jobInfo.getId(), jobInfo.getService(), mergedExtras);
  }

  private PendingIntent createTaskIntent(Context context, String appScopeKey, String taskName, int flags) {
    if (context == null) {
      return null;
    }

    Intent intent = new Intent(TaskBroadcastReceiver.INTENT_ACTION, null, context, TaskBroadcastReceiver.class);

    // query param is called appId for legacy reasons
    Uri dataUri = new Uri.Builder()
        .appendQueryParameter("appId", appScopeKey)
        .appendQueryParameter("taskName", taskName)
        .build();

    intent.setData(dataUri);

    // We're defaulting to the behaviour prior API 31 (mutable) even though Android
    // recommends immutability
    int mutableFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
    return PendingIntent.getBroadcast(context, PENDING_INTENT_REQUEST_CODE, intent, flags | mutableFlag);
  }

  private PendingIntent createTaskIntent(Context context, TaskInterface task, int flags) {
    String appScopeKey = task.getAppScopeKey();
    String taskName = task.getName();

    return createTaskIntent(context, appScopeKey, taskName, flags);
  }

  private JobInfo createJobInfo(int jobId, ComponentName jobService, PersistableBundle extras) {
    return new JobInfo.Builder(jobId, jobService)
        .setExtras(extras)
        .setMinimumLatency(0)
        .setOverrideDeadline(DEFAULT_OVERRIDE_DEADLINE)
        .build();
  }

  private JobInfo createJobInfo(Context context, TaskInterface task, int jobId, List<PersistableBundle> data) {
    return createJobInfo(jobId, new ComponentName(context, TaskJobService.class), createExtrasForTask(task, data));
  }

  private PersistableBundle createExtrasForTask(TaskInterface task, List<PersistableBundle> data) {
    PersistableBundle extras = new PersistableBundle();

    // persistable bundle extras key is called appId for legacy reasons
    extras.putInt(EXTRAS_REQUIRED_KEY, 1);
    extras.putString("appId", task.getAppScopeKey());
    extras.putString("taskName", task.getName());
    extras.putLong("scheduledTime", System.currentTimeMillis());

    if (data != null) {
      extras.putInt("dataSize", data.size());

      for (int i = 0; i < data.size(); i++) {
        extras.putPersistableBundle(String.valueOf(i), data.get(i));
      }
    } else {
      extras.putInt("dataSize", 0);
    }

    return extras;
  }

  private boolean isJobInfoRelatedToTask(JobInfo jobInfo, TaskInterface task) {
    PersistableBundle extras = jobInfo.getExtras();

    // persistable bundle extras key is called appId for legacy reasons
    String appScopeKey = task.getAppScopeKey();
    String taskName = task.getName();

    if (extras.containsKey(EXTRAS_REQUIRED_KEY)) {
      return appScopeKey.equals(extras.getString("appId", "")) && taskName.equals(extras.getString("taskName", ""));
    }
    return false;
  }

  // endregion private helpers
  // region converting map to bundle

  @SuppressWarnings("unchecked")
  static Bundle mapToBundle(Map<String, Object> map) {
    Bundle bundle = new Bundle();

    for (Map.Entry<String, Object> entry : map.entrySet()) {
      Object value = entry.getValue();
      String key = entry.getKey();

      if (value instanceof Double) {
        bundle.putDouble(key, (Double) value);
      } else if (value instanceof Integer) {
        bundle.putInt(key, (Integer) value);
      } else if (value instanceof String) {
        bundle.putString(key, (String) value);
      } else if (value instanceof Boolean) {
        bundle.putBoolean(key, (Boolean) value);
      } else if (value instanceof List) {
        List<Object> list = (List<Object>) value;
        Object first = list.get(0);

        if (first == null || first instanceof Double) {
          bundle.putDoubleArray(key, listToDoubleArray(list));
        } else if (first instanceof Integer) {
          bundle.putIntArray(key, listToIntArray(list));
        } else if (first instanceof String) {
          bundle.putStringArray(key, listToStringArray(list));
        } else if (first instanceof Map) {
          bundle.putParcelableArrayList(key, listToParcelableArrayList(list));
        }
      } else if (value instanceof Map) {
        bundle.putBundle(key, mapToBundle((Map<String, Object>) value));
      }
    }
    return bundle;
  }

  @SuppressWarnings("unchecked")
  private static double[] listToDoubleArray(List<Object> list) {
    double[] doubles = new double[list.size()];
    for (int i = 0; i < list.size(); i++) {
      doubles[i] = (Double) list.get(i);
    }
    return doubles;
  }

  @SuppressWarnings("unchecked")
  private static int[] listToIntArray(List<Object> list) {
    int[] integers = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      integers[i] = (Integer) list.get(i);
    }
    return integers;
  }

  @SuppressWarnings("unchecked")
  private static String[] listToStringArray(List<Object> list) {
    String[] strings = new String[list.size()];
    for (int i = 0; i < list.size(); i++) {
      strings[i] = list.get(i).toString();
    }
    return strings;
  }

  @SuppressWarnings("unchecked")
  private static ArrayList<Parcelable> listToParcelableArrayList(List<Object> list) {
    ArrayList<Parcelable> arrayList = new ArrayList<>();

    for (Object item : list) {
      Map<String, Object> map = (Map<String, Object>) item;
      arrayList.add(mapToBundle(map));
    }
    return arrayList;
  }

  // endregion converting map to bundle
}
