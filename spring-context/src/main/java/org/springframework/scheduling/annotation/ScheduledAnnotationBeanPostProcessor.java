/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.scheduling.annotation;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Bean post-processor that registers methods annotated with @{@link Scheduled}
 * to be invoked by a {@link org.springframework.scheduling.TaskScheduler} according
 * to the "fixedRate", "fixedDelay", or "cron" expression provided via the annotation.
 *
 * <p>This post-processor is automatically registered by Spring's
 * {@code <task:annotation-driven>} XML element, and also by the
 * {@link EnableScheduling @EnableScheduling} annotation.
 *
 * <p>Autodetects any {@link SchedulingConfigurer} instances in the container,
 * allowing for customization of the scheduler to be used or for fine-grained
 * control over task registration (e.g. registration of {@link Trigger} tasks.
 * See the @{@link EnableScheduling} javadocs for complete usage details.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Elizabeth Chatman
 * @since 3.0
 * @see Scheduled
 * @see EnableScheduling
 * @see SchedulingConfigurer
 * @see org.springframework.scheduling.TaskScheduler
 * @see org.springframework.scheduling.config.ScheduledTaskRegistrar
 * @see AsyncAnnotationBeanPostProcessor
 */
/**
 * @EnableScheduling--->@Import(SchedulingConfiguration)--->ScheduledAnnotationBeanPostProcessor
 * 解析和处理每一个符合定义类Bean中有注解@Scheduled注解的方法，根据注解内容封装成不同的Task实例存储到ScheduledTaskRegistrar中
 * 钩子函数：afterSingletonsInstantiated()在所有单例初始化完成之后回调触发，
 * 		   设置ScheduledTaskRegistrar中的任务调度器（TaskScheduler或者ScheduledExecutorService类型）实例，
 * 		   并且调用ScheduledTaskRegistrar#afterPropertiesSet()方法添加所有缓存的Task实例到任务调度器中执行。
 *
 * ScheduledTaskHolder：返回Set<ScheduledTask>，表示持有的所有任务实例。
 * MergedBeanDefinitionPostProcessor：Bean定义合并时回调，预留空实现，暂时不做任何处理
 * BeanPostProcessor接口：MergedBeanDefinitionPostProcessor的父接口，Bean实例初始化前后分别回调，
 * 		后回调postProcessAfterInitialization()方法就是用于解析@Scheduled和装载ScheduledTask
 * DestructionAwareBeanPostProcessor：具体的Bean实例销毁的时候回调，用于Bean实例销毁的时候移除和取消对应的任务实例。
 * Ordered：用于Bean加载时候的排序，改变ScheduledAnnotationBeanPostProcessor在BeanPostProcessor执行链中的顺序。
 * EmbeddedValueResolverAware：回调StringValueResolver实例，用于解析带占位符的环境变量属性值。
 * BeanNameAware：回调BeanName。
 * BeanFactoryAware：回调BeanFactory实例，具体是DefaultListableBeanFactory，也就是熟知的IOC容器。
 * ApplicationContextAware：回调ApplicationContext实例，Spring上下文，它是IOC容器的门面，同时是事件广播器、资源加载器的实现等等。
 * SmartInitializingSingleton：所有单例实例化完毕之后回调，作用是在持有的applicationContext为NULL的时候开始调度所有加载完成的任务
 * ApplicationListener：监听Spring应用的事件，具体是ApplicationListener<ContextRefreshedEvent>，监听上下文刷新的事件，
 * 		事件中携带的ApplicationContext实例和ApplicationContextAware回调的ApplicationContext实例一致，
 * 		那么在此监听回调方法中开始调度所有加载完成的任务，也就是在ScheduledAnnotationBeanPostProcessor这个类中，
 * 		SmartInitializingSingleton接口的实现和ApplicationListener接口的实现逻辑是互斥的。
 * DisposableBean：当前Bean实例销毁时候回调，也就是ScheduledAnnotationBeanPostProcessor自身被销毁的时候回调，
 * 		用于取消和清理所有的ScheduledTask。
 */
public class ScheduledAnnotationBeanPostProcessor
		implements ScheduledTaskHolder, MergedBeanDefinitionPostProcessor, DestructionAwareBeanPostProcessor,
		Ordered, EmbeddedValueResolverAware, BeanNameAware, BeanFactoryAware, ApplicationContextAware,
		SmartInitializingSingleton, ApplicationListener<ContextRefreshedEvent>, DisposableBean {

	/**
	 * The default name of the {@link TaskScheduler} bean to pick up: {@value}.
	 * <p>Note that the initial lookup happens by type; this is just the fallback
	 * in case of multiple scheduler beans found in the context.
	 * @since 4.2
	 */
	public static final String DEFAULT_TASK_SCHEDULER_BEAN_NAME = "taskScheduler";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private Object scheduler;

	@Nullable
	private StringValueResolver embeddedValueResolver;

	@Nullable
	private String beanName;

	@Nullable
	private BeanFactory beanFactory;

	@Nullable
	private ApplicationContext applicationContext;

	private final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

	private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));

	private final Map<Object, Set<ScheduledTask>> scheduledTasks = new IdentityHashMap<>(16);


	@Override
	public int getOrder() {
		return LOWEST_PRECEDENCE;
	}

	/**
	 * Set the {@link org.springframework.scheduling.TaskScheduler} that will invoke
	 * the scheduled methods, or a {@link java.util.concurrent.ScheduledExecutorService}
	 * to be wrapped as a TaskScheduler.
	 * <p>If not specified, default scheduler resolution will apply: searching for a
	 * unique {@link TaskScheduler} bean in the context, or for a {@link TaskScheduler}
	 * bean named "taskScheduler" otherwise; the same lookup will also be performed for
	 * a {@link ScheduledExecutorService} bean. If neither of the two is resolvable,
	 * a local single-threaded default scheduler will be created within the registrar.
	 * @see #DEFAULT_TASK_SCHEDULER_BEAN_NAME
	 */
	public void setScheduler(Object scheduler) {
		this.scheduler = scheduler;
	}

	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}

	@Override
	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	/**
	 * Making a {@link BeanFactory} available is optional; if not set,
	 * {@link SchedulingConfigurer} beans won't get autodetected and
	 * a {@link #setScheduler scheduler} has to be explicitly configured.
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Setting an {@link ApplicationContext} is optional: If set, registered
	 * tasks will be activated in the {@link ContextRefreshedEvent} phase;
	 * if not set, it will happen at {@link #afterSingletonsInstantiated} time.
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		if (this.beanFactory == null) {
			this.beanFactory = applicationContext;
		}
	}


	@Override
	public void afterSingletonsInstantiated() {
		// Remove resolved singleton classes from cache
		this.nonAnnotatedClasses.clear();

		if (this.applicationContext == null) {
			// Not running in an ApplicationContext -> register tasks early...
			finishRegistration();
		}
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		if (event.getApplicationContext() == this.applicationContext) {
			// Running in an ApplicationContext -> register tasks this late...
			// giving other ContextRefreshedEvent listeners a chance to perform
			// their work at the same time (e.g. Spring Batch's job registration).
			finishRegistration();
		}
	}

	private void finishRegistration() {
		// 如果持有的scheduler对象不为null则设置ScheduledTaskRegistrar中的任务调度器
		if (this.scheduler != null) {
			this.registrar.setScheduler(this.scheduler);
		}

		// 这个判断一般会成立，得到的BeanFactory就是DefaultListableBeanFactory
		if (this.beanFactory instanceof ListableBeanFactory) {
			// 获取所有的调度配置器SchedulingConfigurer实例，
			// 并且都回调configureTasks()方法，这个很重要，它是用户动态装载调取任务的扩展钩子接口
			Map<String, SchedulingConfigurer> beans =
					((ListableBeanFactory) this.beanFactory).getBeansOfType(SchedulingConfigurer.class);
			List<SchedulingConfigurer> configurers = new ArrayList<>(beans.values());
			// SchedulingConfigurer实例列表排序
			AnnotationAwareOrderComparator.sort(configurers);
			for (SchedulingConfigurer configurer : configurers) {
				configurer.configureTasks(this.registrar);
			}
		}

		// 从BeanFactory取出任务调度器实例
		// 主要判断TaskScheduler或者ScheduledExecutorService类型的Bean，包括尝试通过类型或者名字获取
		// 获取成功后设置到ScheduledTaskRegistrar中
		if (this.registrar.hasTasks() && this.registrar.getScheduler() == null) {
			Assert.state(this.beanFactory != null, "BeanFactory must be set to find scheduler by type");
			try {
				// Search for TaskScheduler bean...
				this.registrar.setTaskScheduler(resolveSchedulerBean(this.beanFactory, TaskScheduler.class, false));
			}
			catch (NoUniqueBeanDefinitionException ex) {
				logger.debug("Could not find unique TaskScheduler bean", ex);
				try {
					this.registrar.setTaskScheduler(resolveSchedulerBean(this.beanFactory, TaskScheduler.class, true));
				}
				catch (NoSuchBeanDefinitionException ex2) {
					if (logger.isInfoEnabled()) {
						logger.info("More than one TaskScheduler bean exists within the context, and " +
								"none is named 'taskScheduler'. Mark one of them as primary or name it 'taskScheduler' " +
								"(possibly as an alias); or implement the SchedulingConfigurer interface and call " +
								"ScheduledTaskRegistrar#setScheduler explicitly within the configureTasks() callback: " +
								ex.getBeanNamesFound());
					}
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				logger.debug("Could not find default TaskScheduler bean", ex);
				// Search for ScheduledExecutorService bean next...
				try {
					this.registrar.setScheduler(resolveSchedulerBean(this.beanFactory, ScheduledExecutorService.class, false));
				}
				catch (NoUniqueBeanDefinitionException ex2) {
					logger.debug("Could not find unique ScheduledExecutorService bean", ex2);
					try {
						this.registrar.setScheduler(resolveSchedulerBean(this.beanFactory, ScheduledExecutorService.class, true));
					}
					catch (NoSuchBeanDefinitionException ex3) {
						if (logger.isInfoEnabled()) {
							logger.info("More than one ScheduledExecutorService bean exists within the context, and " +
									"none is named 'taskScheduler'. Mark one of them as primary or name it 'taskScheduler' " +
									"(possibly as an alias); or implement the SchedulingConfigurer interface and call " +
									"ScheduledTaskRegistrar#setScheduler explicitly within the configureTasks() callback: " +
									ex2.getBeanNamesFound());
						}
					}
				}
				catch (NoSuchBeanDefinitionException ex2) {
					logger.debug("Could not find default ScheduledExecutorService bean", ex2);
					// Giving up -> falling back to default scheduler within the registrar...
					logger.info("No TaskScheduler/ScheduledExecutorService bean found for scheduled processing");
				}
			}
		}

		this.registrar.afterPropertiesSet();
	}

	private <T> T resolveSchedulerBean(BeanFactory beanFactory, Class<T> schedulerType, boolean byName) {
		if (byName) {
			T scheduler = beanFactory.getBean(DEFAULT_TASK_SCHEDULER_BEAN_NAME, schedulerType);
			if (this.beanName != null && this.beanFactory instanceof ConfigurableBeanFactory) {
				((ConfigurableBeanFactory) this.beanFactory).registerDependentBean(
						DEFAULT_TASK_SCHEDULER_BEAN_NAME, this.beanName);
			}
			return scheduler;
		}
		else if (beanFactory instanceof AutowireCapableBeanFactory) {
			NamedBeanHolder<T> holder = ((AutowireCapableBeanFactory) beanFactory).resolveNamedBean(schedulerType);
			if (this.beanName != null && beanFactory instanceof ConfigurableBeanFactory) {
				((ConfigurableBeanFactory) beanFactory).registerDependentBean(holder.getBeanName(), this.beanName);
			}
			return holder.getBeanInstance();
		}
		else {
			return beanFactory.getBean(schedulerType);
		}
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof AopInfrastructureBean) {
			// Ignore AOP infrastructure such as scoped proxies.
			return bean;
		}

		// 获取Bean的用户态类型，例如Bean有可能被CGLIB增强，这个时候要取其父类
		Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
		// nonAnnotatedClasses存放着不存在@Scheduled注解的类型，缓存起来避免重复判断它是否携带@Scheduled注解的方法
		if (!this.nonAnnotatedClasses.contains(targetClass)) {
			// 因为JDK8之后支持重复注解，因此获取具体类型中Method -> @Scheduled的集合，
			// 也就是有可能一个方法使用多个@Scheduled注解，最终会封装为多个Task
			Map<Method, Set<Scheduled>> annotatedMethods = MethodIntrospector.selectMethods(targetClass,
					(MethodIntrospector.MetadataLookup<Set<Scheduled>>) method -> {
						Set<Scheduled> scheduledMethods = AnnotatedElementUtils.getMergedRepeatableAnnotations(
								method, Scheduled.class, Schedules.class);
						return (!scheduledMethods.isEmpty() ? scheduledMethods : null);
					});
			// 解析到类型中不存在@Scheduled注解的方法添加到nonAnnotatedClasses缓存
			if (annotatedMethods.isEmpty()) {
				this.nonAnnotatedClasses.add(targetClass);
				if (logger.isTraceEnabled()) {
					logger.trace("No @Scheduled annotations found on bean class: " + targetClass);
				}
			}
			else {
				// Method -> @Scheduled的集合遍历processScheduled()方法进行登记
//				processScheduled(Scheduled scheduled, Method method, Object bean)就是具体的注解解析和Task封装的方法：
				// Non-empty set of methods
				annotatedMethods.forEach((method, scheduledMethods) ->
						scheduledMethods.forEach(scheduled -> processScheduled(scheduled, method, bean)));
				if (logger.isDebugEnabled()) {
					logger.debug(annotatedMethods.size() + " @Scheduled methods processed on bean '" + beanName +
							"': " + annotatedMethods);
				}
			}
		}
		return bean;
	}

	/**
	 * Process the given {@code @Scheduled} method declaration on the given bean.
	 * @param scheduled the @Scheduled annotation
	 * @param method the method that the annotation has been declared on
	 * @param bean the target bean instance
	 */
	// 0. 解析@Scheduled中的initialDelay、initialDelayString属性，适用于FixedDelayTask或者FixedRateTask的延迟执行
	// 1. 优先解析@Scheduled中的cron属性，封装为CronTask，通过ScheduledTaskRegistrar进行缓存
	// 2. 解析@Scheduled中的fixedDelay、fixedDelayString属性，封装为FixedDelayTask，通过ScheduledTaskRegistrar进行缓存
	// 3. 解析@Scheduled中的fixedRate、fixedRateString属性，封装为FixedRateTask，通过ScheduledTaskRegistrar进行缓存
	protected void processScheduled(Scheduled scheduled, Method method, Object bean) {
		try {
			Assert.isTrue(method.getParameterCount() == 0, "Only no-arg methods may be annotated with @Scheduled");

			Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());
			// 通过方法宿主Bean和目标方法封装Runnable适配器ScheduledMethodRunnable实例
			Runnable runnable = new ScheduledMethodRunnable(bean, invocableMethod);
			boolean processedSchedule = false;
			String errorMessage =
					"Exactly one of the 'cron', 'fixedDelay(String)', or 'fixedRate(String)' attributes is required";

			// 缓存已经装载的任务
			Set<ScheduledTask> tasks = new LinkedHashSet<>(4);

			// Determine initial delay
			// 解析初始化延迟执行时间，initialDelayString支持占位符配置，
			// 如果initialDelayString配置了，会覆盖initialDelay的值
			long initialDelay = scheduled.initialDelay();
			String initialDelayString = scheduled.initialDelayString();
			if (StringUtils.hasText(initialDelayString)) {
				Assert.isTrue(initialDelay < 0, "Specify 'initialDelay' or 'initialDelayString', not both");
				if (this.embeddedValueResolver != null) {
					initialDelayString = this.embeddedValueResolver.resolveStringValue(initialDelayString);
				}
				if (StringUtils.hasLength(initialDelayString)) {
					try {
						initialDelay = parseDelayAsLong(initialDelayString);
					}
					catch (RuntimeException ex) {
						throw new IllegalArgumentException(
								"Invalid initialDelayString value \"" + initialDelayString + "\" - cannot parse into long");
					}
				}
			}

			// Check cron expression
			// 解析时区zone的值，支持支持占位符配置，判断cron是否存在，存在则装载为CronTask
			String cron = scheduled.cron();
			if (StringUtils.hasText(cron)) {
				String zone = scheduled.zone();
				if (this.embeddedValueResolver != null) {
					cron = this.embeddedValueResolver.resolveStringValue(cron);
					zone = this.embeddedValueResolver.resolveStringValue(zone);
				}
				if (StringUtils.hasLength(cron)) {
					Assert.isTrue(initialDelay == -1, "'initialDelay' not supported for cron triggers");
					processedSchedule = true;
					TimeZone timeZone;
					if (StringUtils.hasText(zone)) {
						timeZone = StringUtils.parseTimeZoneString(zone);
					}
					else {
						timeZone = TimeZone.getDefault();
					}
					// 表面上是调度CronTask，实际上由于ScheduledTaskRegistrar不持有TaskScheduler，只是把任务添加到它的缓存中
					// 返回的任务实例添加到宿主Bean的缓存中，然后最后会放入宿主Bean -> List<ScheduledTask>映射中
					tasks.add(this.registrar.scheduleCronTask(new CronTask(runnable, new CronTrigger(cron, timeZone))));
				}
			}

			// At this point we don't need to differentiate between initial delay set or not anymore
			// 修正小于0的初始化延迟执行时间值为0
			if (initialDelay < 0) {
				initialDelay = 0;
			}

			// Check fixed delay
			// 解析fixedDelay和fixedDelayString，
			// 如果同时配置，fixedDelayString最终解析出来的整数值会覆盖fixedDelay，封装为FixedDelayTask
			long fixedDelay = scheduled.fixedDelay();
			if (fixedDelay >= 0) {
				Assert.isTrue(!processedSchedule, errorMessage);
				processedSchedule = true;
				tasks.add(this.registrar.scheduleFixedDelayTask(new FixedDelayTask(runnable, fixedDelay, initialDelay)));
			}
			String fixedDelayString = scheduled.fixedDelayString();
			if (StringUtils.hasText(fixedDelayString)) {
				if (this.embeddedValueResolver != null) {
					fixedDelayString = this.embeddedValueResolver.resolveStringValue(fixedDelayString);
				}
				if (StringUtils.hasLength(fixedDelayString)) {
					Assert.isTrue(!processedSchedule, errorMessage);
					processedSchedule = true;
					try {
						fixedDelay = parseDelayAsLong(fixedDelayString);
					}
					catch (RuntimeException ex) {
						throw new IllegalArgumentException(
								"Invalid fixedDelayString value \"" + fixedDelayString + "\" - cannot parse into long");
					}
					// 表面上是调度FixedDelayTask，实际上由于ScheduledTaskRegistrar不持有TaskScheduler，只是把任务添加到它的缓存中
					// 返回的任务实例添加到宿主Bean的缓存中，然后最后会放入宿主Bean -> List<ScheduledTask>映射中
					tasks.add(this.registrar.scheduleFixedDelayTask(new FixedDelayTask(runnable, fixedDelay, initialDelay)));
				}
			}

			// Check fixed rate
			// 解析fixedRate和fixedRateString，
			// 如果同时配置，fixedRateString最终解析出来的整数值会覆盖fixedRate，封装为FixedRateTask
			long fixedRate = scheduled.fixedRate();
			if (fixedRate >= 0) {
				Assert.isTrue(!processedSchedule, errorMessage);
				processedSchedule = true;
				tasks.add(this.registrar.scheduleFixedRateTask(new FixedRateTask(runnable, fixedRate, initialDelay)));
			}
			String fixedRateString = scheduled.fixedRateString();
			if (StringUtils.hasText(fixedRateString)) {
				if (this.embeddedValueResolver != null) {
					fixedRateString = this.embeddedValueResolver.resolveStringValue(fixedRateString);
				}
				if (StringUtils.hasLength(fixedRateString)) {
					Assert.isTrue(!processedSchedule, errorMessage);
					processedSchedule = true;
					try {
						fixedRate = parseDelayAsLong(fixedRateString);
					}
					catch (RuntimeException ex) {
						throw new IllegalArgumentException(
								"Invalid fixedRateString value \"" + fixedRateString + "\" - cannot parse into long");
					}
					// 表面上是调度FixedRateTask，实际上由于ScheduledTaskRegistrar不持有TaskScheduler，只是把任务添加到它的缓存中
					// 返回的任务实例添加到宿主Bean的缓存中，然后最后会放入宿主Bean -> List<ScheduledTask>映射中
					tasks.add(this.registrar.scheduleFixedRateTask(new FixedRateTask(runnable, fixedRate, initialDelay)));
				}
			}

			// Check whether we had any attribute set
			Assert.isTrue(processedSchedule, errorMessage);

			// Finally register the scheduled tasks
			synchronized (this.scheduledTasks) {
				// 注册所有任务实例，这个映射Key为宿主Bean实例，Value为List<ScheduledTask>，后面用于调度所有注册完成的任务
				Set<ScheduledTask> regTasks = this.scheduledTasks.computeIfAbsent(bean, key -> new LinkedHashSet<>(4));
				regTasks.addAll(tasks);
			}
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"Encountered invalid @Scheduled method '" + method.getName() + "': " + ex.getMessage());
		}
	}

	private static long parseDelayAsLong(String value) throws RuntimeException {
		if (value.length() > 1 && (isP(value.charAt(0)) || isP(value.charAt(1)))) {
			return Duration.parse(value).toMillis();
		}
		return Long.parseLong(value);
	}

	private static boolean isP(char ch) {
		return (ch == 'P' || ch == 'p');
	}


	/**
	 * Return all currently scheduled tasks, from {@link Scheduled} methods
	 * as well as from programmatic {@link SchedulingConfigurer} interaction.
	 * @since 5.0.2
	 */
	@Override
	public Set<ScheduledTask> getScheduledTasks() {
		Set<ScheduledTask> result = new LinkedHashSet<>();
		synchronized (this.scheduledTasks) {
			Collection<Set<ScheduledTask>> allTasks = this.scheduledTasks.values();
			for (Set<ScheduledTask> tasks : allTasks) {
				result.addAll(tasks);
			}
		}
		result.addAll(this.registrar.getScheduledTasks());
		return result;
	}

	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) {
		Set<ScheduledTask> tasks;
		synchronized (this.scheduledTasks) {
			tasks = this.scheduledTasks.remove(bean);
		}
		if (tasks != null) {
			for (ScheduledTask task : tasks) {
				task.cancel();
			}
		}
	}

	@Override
	public boolean requiresDestruction(Object bean) {
		synchronized (this.scheduledTasks) {
			return this.scheduledTasks.containsKey(bean);
		}
	}

	@Override
	public void destroy() {
		synchronized (this.scheduledTasks) {
			Collection<Set<ScheduledTask>> allTasks = this.scheduledTasks.values();
			for (Set<ScheduledTask> tasks : allTasks) {
				for (ScheduledTask task : tasks) {
					task.cancel();
				}
			}
			this.scheduledTasks.clear();
		}
		this.registrar.destroy();
	}

}
