package expo.modules.taskManager;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class TaskJobService extends JobService {

  // Track currently executing job IDs to prevent race conditions when merging.
  // A job that is executing should never be cancelled/rescheduled.
  private static final Set<Integer> sExecutingJobIds = Collections.synchronizedSet(new HashSet<Integer>());

  /**
   * Check if a job is currently executing. Used to prevent race conditions
   * where we might try to cancel/reschedule a job that's already running.
   */
  public static boolean isJobExecuting(int jobId) {
    return sExecutingJobIds.contains(jobId);
  }

  /**
   * Mark a job as finished executing. Called when job completes or is cancelled.
   */
  public static void markJobFinished(int jobId) {
    sExecutingJobIds.remove(jobId);
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    int jobId = params.getJobId();

    // Mark this job as executing to prevent race conditions
    sExecutingJobIds.add(jobId);

    Context context = getApplicationContext();
    TaskService taskService = new TaskService(context);

    return taskService.handleJob(this, params);
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    int jobId = params.getJobId();

    // Job is no longer executing
    sExecutingJobIds.remove(jobId);

    Context context = getApplicationContext();
    TaskService taskService = new TaskService(context);

    return taskService.cancelJob(this, params);
  }
}
