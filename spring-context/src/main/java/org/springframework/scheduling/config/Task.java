/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.scheduling.config;

import org.springframework.util.Assert;

/**
 * Holder class defining a {@code Runnable} to be executed as a task, typically at a
 * scheduled time or interval. See subclass hierarchy for various scheduling approaches.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.2
 * 分类：
 * 1.Cron表达式：
 * 		支持通过Cron表达式配置执行的周期，对应org.springframework.scheduling.config.CronTask
 * 2.固定延迟间隔任务：
 * 		 上一轮执行完毕后间隔固定周期再次执行，对于org.springframework.scheduling.config.FixedDelayTask
 * 3.固定频率任务：
 * 		 基于固定的间隔时间执行，不会理会上一轮是否执行完毕本轮都会执行，对应org.springframework.scheduling.config.FixedRateTask
 */
public class Task {

	private final Runnable runnable;


	/**
	 * Create a new {@code Task}.
	 * @param runnable the underlying task to execute
	 */
	public Task(Runnable runnable) {
		Assert.notNull(runnable, "Runnable must not be null");
		this.runnable = runnable;
	}


	/**
	 * Return the underlying task.
	 */
	public Runnable getRunnable() {
		return this.runnable;
	}


	@Override
	public String toString() {
		return this.runnable.toString();
	}

}
