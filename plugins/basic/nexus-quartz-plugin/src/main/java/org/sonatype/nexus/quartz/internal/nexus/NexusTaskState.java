/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2014 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.quartz.internal.nexus;

import java.util.Date;
import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo.EndState;
import org.sonatype.nexus.scheduling.TaskInfo.LastRunState;
import org.sonatype.nexus.scheduling.TaskInfo.State;
import org.sonatype.nexus.scheduling.schedule.Schedule;

import org.quartz.JobDataMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Holder for state of a task. Pushed by {@link NexusTaskJobListener}.
 *
 * @since 3.0
 */
public class NexusTaskState
{
  private final TaskConfiguration taskConfiguration;

  private final Schedule schedule;

  private final State state;

  private final Date nextExecutionTime;

  public NexusTaskState(final State state,
                        final TaskConfiguration taskConfiguration,
                        final Schedule schedule,
                        final @Nullable Date nextExecutionTime)
  {
    this.taskConfiguration = checkNotNull(taskConfiguration);
    this.schedule = checkNotNull(schedule);
    this.state = checkNotNull(state);
    this.nextExecutionTime = nextExecutionTime;
  }

  public TaskConfiguration getConfiguration() {
    return taskConfiguration;
  }

  public Schedule getSchedule() {
    return schedule;
  }

  public State getState() {
    return state;
  }

  public Date getNextExecutionTime() {
    return nextExecutionTime;
  }

  @Nullable
  public LastRunState getLastRunState() {
    if (taskConfiguration.getMap().containsKey("lastRunState.endState")) {
      final String endStateString = taskConfiguration.getString("lastRunState.endState");
      final long runStarted = taskConfiguration.getLong("lastRunState.runStarted", -1);
      final long runDuration = taskConfiguration.getLong("lastRunState.runDuration", -1);
      return new LS(EndState.valueOf(endStateString), new Date(runStarted), runDuration);
    }
    return null;
  }

  /**
   * Helper to set ending state on a map. The maps might be {@link JobDataMap} or {@link TaskConfiguration}.
   */
  public static void setLastRunState(final Map config,
                                     final EndState endState,
                                     final Date runStarted,
                                     final long runDuration)
  {
    checkNotNull(config);
    checkNotNull(endState);
    checkNotNull(runStarted);
    checkArgument(runDuration >= 0);
    config.put("lastRunState.endState", endState.name());
    config.put("lastRunState.runStarted", Long.toString(runStarted.getTime()));
    config.put("lastRunState.runDuration", Long.toString(runDuration));
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "taskConfiguration=" + taskConfiguration +
        ", schedule=" + schedule +
        ", state=" + state +
        ", nextExecutionTime=" + nextExecutionTime +
        '}';
  }

  static class LS
      implements LastRunState
  {
    private final EndState endState;

    private final Date runStarted;

    private final long runDuration;

    public LS(final EndState endState, final Date runStarted, final long runDuration) {
      this.endState = endState;
      this.runStarted = runStarted;
      this.runDuration = runDuration;
    }

    @Override
    public EndState getEndState() {
      return endState;
    }

    @Override
    public Date getRunStarted() {
      return runStarted;
    }

    @Override
    public long getRunDuration() {
      return runDuration;
    }
  }
}