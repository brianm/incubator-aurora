package com.twitter.mesos.executor;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twitter.common.base.Closure;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Data;
import com.twitter.common.quantity.Time;

import java.io.File;
import java.io.FileFilter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages system resources and periodically gathers information about resource consumption by
 * tasks.
 *
 * @author wfarner
 */
public class ResourceManager {
  private static final Logger LOG = Logger.getLogger(ResourceManager.class.getName());

  // TODO(wfarner): These need to be configurable.
  private static final Amount<Long, Time> FILE_EXPIRATION_TIME = Amount.of(1L, Time.DAYS);
  private static final Amount<Long, Data> MAX_DISK_SPACE = Amount.of(20L, Data.GB);

  private final File managedDir;
  private final TaskManager taskManager;
  private final ResourceScanner resourceScanner;

  public ResourceManager(TaskManager taskManager, File managedDir) {
    this.managedDir = Preconditions.checkNotNull(managedDir);
    Preconditions.checkArgument(managedDir.exists(),
        "Managed directory does not exist: " + managedDir);

    this.taskManager = Preconditions.checkNotNull(taskManager);
    this.resourceScanner = new LinuxProcScraper();
  }

  public void start() {
    // TODO(wfarner): Enable when this has been tested.
    //startResourceScanner();
    startDiskGc();
  }

  private void startResourceScanner() {
    Runnable scanner = new Runnable() {
      @Override public void run() {
        for (RunningTask task : taskManager.getRunningTasks()) {
          // TODO(wfarner): Need to track rate of jiffies to determine CPU usage.
          ResourceScanner.ProcInfo procInfo =
              resourceScanner.getResourceUsage(task.getProcessId(), task.getSandboxDir());
          task.getResourceConsumption()
              .setDiskUsedMb(procInfo.getDiskUsed().as(Data.MB).intValue())
              .setMemUsedMb(procInfo.getVmSize().as(Data.MB).intValue());

          LOG.info("Resource usage for task " + task.getTaskId() + ": " + procInfo);
        }
      }
    };

    ScheduledExecutorService scannerExecutor = new ScheduledThreadPoolExecutor(1,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("Proc-Scraper-%d").build());
    scannerExecutor.scheduleAtFixedRate(scanner, 10, 10, TimeUnit.SECONDS);
  }

  private void startDiskGc() {
    FileFilter expiredOrUnknown = new FileFilter() {
        @Override public boolean accept(File file) {
          if (!file.isDirectory()) return false;

          String dirName = file.getName();
          int taskId;
          try {
            taskId = Integer.parseInt(dirName);
          } catch (NumberFormatException e) {
            return true;
          }

          // Always delete unknown directories.
          if (!taskManager.hasTask(taskId)) return true;

          // If the directory is for a known task, only delete when it has expired.
          long timeSinceLastModify =
              System.currentTimeMillis() - DiskGarbageCollector.recursiveLastModified(file);
          return timeSinceLastModify > FILE_EXPIRATION_TIME.as(Time.MILLISECONDS);
        }
      };

    Closure<File> gcCallback = new Closure<File>() {
      @Override public void execute(File file) throws RuntimeException {
        String dirName = file.getName();
        int taskId;
        try {
          taskId = Integer.parseInt(dirName);
        } catch (NumberFormatException e) {
          return; // No-op.
        }

        LOG.info("Removing record for garbage-collected task "  + taskId);
        taskManager.deleteCompletedTask(taskId);
      }
    };

    // The expired file GC always runs, and expunges all directories that are unknown or too old.
    DiskGarbageCollector expiredDirGc = new DiskGarbageCollector("ExpiredOrUnknownDir",
        managedDir, expiredOrUnknown, gcCallback);

    FileFilter completedTaskFileFilter = new FileFilter() {
        @Override public boolean accept(File file) {
          if (!file.isDirectory()) return false;

          String dirName = file.getName();
          int taskId;
          try {
            taskId = Integer.parseInt(dirName);
          } catch (NumberFormatException e) {
            LOG.info("Unrecognized file found while garbage collecting: " + file);
            return true;
          }

          return !taskManager.isRunning(taskId);
        }
      };

    // The completed task GC only runs when disk is exhausted, and removes directories for tasks
    // that have completed.
    DiskGarbageCollector completedTaskGc = new DiskGarbageCollector("CompletedTask",
        managedDir, completedTaskFileFilter, MAX_DISK_SPACE, gcCallback);

    // TODO(wfarner): Make GC intervals configurable.
    ScheduledExecutorService gcExecutor = new ScheduledThreadPoolExecutor(2,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("Disk GC-%d").build());
    gcExecutor.scheduleAtFixedRate(expiredDirGc, 1, 5, TimeUnit.MINUTES);
    gcExecutor.scheduleAtFixedRate(completedTaskGc, 2, 1, TimeUnit.MINUTES);
  }
}