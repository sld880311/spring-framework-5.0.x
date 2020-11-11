/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.transaction.config;

import org.w3c.dom.Element;

import org.springframework.aop.config.AopNamespaceUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser
 * BeanDefinitionParser} implementation that allows users to easily configure
 * all the infrastructure beans required to enable annotation-driven transaction
 * demarcation.
 *
 * <p>By default, all proxies are created as JDK proxies. This may cause some
 * problems if you are injecting objects as concrete classes rather than
 * interfaces. To overcome this restriction you can set the
 * '{@code proxy-target-class}' attribute to '{@code true}', which
 * will result in class-based proxies being created.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @author Stephane Nicoll
 * @since 2.0
 */
class AnnotationDrivenBeanDefinitionParser implements BeanDefinitionParser {

	/**
	 * Parses the {@code <tx:annotation-driven/>} tag. Will
	 * {@link AopNamespaceUtils#registerAutoProxyCreatorIfNecessary register an AutoProxyCreator}
	 * with the container as necessary.
	 */
	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		// 注册事务相关的监听器，如果某个方法标注了TransactionalEventListener注解，
		// 那么该方法就是一个事务事件触发方法，即发生某种事务事件后，将会根据该注解的设置，回调指定
		// 类型的方法。常见的事务事件有：事务执行前和事务完成(包括报错后的完成)后的事件。
		registerTransactionalEventListenerFactory(parserContext);
		String mode = element.getAttribute("mode");
		// 获取当前事务驱动程序的模式，如果使用了aspectj模式，则会注册一个AnnotationTransactionAspect
		// 类型的bean，用户可以以aspectj的方式使用该bean对事务进行更多的配置
		if ("aspectj".equals(mode)) {
			// mode="aspectj"
			registerTransactionAspect(element, parserContext);
		}
		else {
			// mode="proxy"
			/**
			 * BeanFactoryTransactionAttributeSourceAdvisor：
			 * 		封装了实现事务所需的所有属性，包括Pointcut，Advice，TransactionManager以及一些其他的在Transactional注解中声明的属性；
			 * 		TransactionAttributeSourcePointcut：
			 * 			用于判断哪些bean需要织入当前的事务逻辑
			 * 			判断其方法或类声明上有没有使用@Transactional注解，如果使用了就是需要织入事务逻辑的bean;
			 * TransactionInterceptor：
			 * 		这个bean本质上是一个Advice，其封装了当前需要织入目标bean的切面逻辑，
			 * 		也就是Spring事务是如果借助于数据库事务来实现对目标方法的环绕的。
			 * AnnotationTransactionAttributeSource:通过@Transactional注解获取TransactionAttribute信息
			 */

			AopAutoProxyConfigurer.configureAutoProxyCreator(element, parserContext);
		}
		return null;
	}

	private void registerTransactionAspect(Element element, ParserContext parserContext) {
		String txAspectBeanName = TransactionManagementConfigUtils.TRANSACTION_ASPECT_BEAN_NAME;
		String txAspectClassName = TransactionManagementConfigUtils.TRANSACTION_ASPECT_CLASS_NAME;
		if (!parserContext.getRegistry().containsBeanDefinition(txAspectBeanName)) {
			RootBeanDefinition def = new RootBeanDefinition();
			def.setBeanClassName(txAspectClassName);
			def.setFactoryMethodName("aspectOf");
			registerTransactionManager(element, def);
			parserContext.registerBeanComponent(new BeanComponentDefinition(def, txAspectBeanName));
		}
	}

	private static void registerTransactionManager(Element element, BeanDefinition def) {
		def.getPropertyValues().add("transactionManagerBeanName",
				TxNamespaceHandler.getTransactionManagerName(element));
	}

	private void registerTransactionalEventListenerFactory(ParserContext parserContext) {
		RootBeanDefinition def = new RootBeanDefinition();
		def.setBeanClass(TransactionalEventListenerFactory.class);
		parserContext.registerBeanComponent(new BeanComponentDefinition(def,
				TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME));
	}


	/**
	 * Inner class to just introduce an AOP framework dependency when actually in proxy mode.
	 */
	private static class AopAutoProxyConfigurer {

		public static void configureAutoProxyCreator(Element element, ParserContext parserContext) {
			// 这个方法主要是在当前BeanFactory中注册InfrastructureAdvisorAutoProxyCreator这个
			// bean，这个bean继承了AbstractAdvisorAutoProxyCreator
			AopNamespaceUtils.registerAutoProxyCreatorIfNecessary(parserContext, element);

			// 这里的txAdvisorBeanName就是我们最终要注册的bean，其类型就是下面注册的
			// BeanFactoryTransactionAttributeSourceAdvisor，可以看到，其本质是一个
			// Advisor类型的对象，因而Spring Aop会将其作为一个切面织入到指定的bean中
			String txAdvisorBeanName = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME;
			// 如果当前BeanFactory中已经存在了目标bean，则不进行注册
			if (!parserContext.getRegistry().containsBeanDefinition(txAdvisorBeanName)) {
				Object eleSource = parserContext.extractSource(element);

				// Create the TransactionAttributeSource definition.
				// 注册AnnotationTransactionAttributeSource，这个bean的主要作用是封装
				// @Transactional注解中声明的各个属性
				RootBeanDefinition sourceDef = new RootBeanDefinition(
						"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource");
				sourceDef.setSource(eleSource);
				sourceDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				String sourceName = parserContext.getReaderContext().registerWithGeneratedName(sourceDef);

				// 注册TransactionInterceptor类型的bean，并且将上面的封装属性的bean设置为其一个属性。
				// 这个bean本质上是一个Advice(可查看其继承结构)，Spring Aop使用Advisor封装实现切面
				// 逻辑织入所需的所有属性，但真正的切面逻辑却是保存在其Advice属性中的，也就是说这里的
				// TransactionInterceptor才是真正封装了事务切面逻辑的bean
				// Create the TransactionInterceptor definition.
				RootBeanDefinition interceptorDef = new RootBeanDefinition(TransactionInterceptor.class);
				interceptorDef.setSource(eleSource);
				interceptorDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				registerTransactionManager(element, interceptorDef);
				interceptorDef.getPropertyValues().add("transactionAttributeSource", new RuntimeBeanReference(sourceName));
				String interceptorName = parserContext.getReaderContext().registerWithGeneratedName(interceptorDef);

				// 注册BeanFactoryTransactionAttributeSourceAdvisor类型的bean，这个bean实现了
				// Advisor接口，实际上就是封装了当前需要织入的切面的所有所需的属性
				// Create the TransactionAttributeSourceAdvisor definition.
				RootBeanDefinition advisorDef = new RootBeanDefinition(BeanFactoryTransactionAttributeSourceAdvisor.class);
				advisorDef.setSource(eleSource);
				advisorDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				advisorDef.getPropertyValues().add("transactionAttributeSource", new RuntimeBeanReference(sourceName));
				advisorDef.getPropertyValues().add("adviceBeanName", interceptorName);
				if (element.hasAttribute("order")) {
					advisorDef.getPropertyValues().add("order", element.getAttribute("order"));
				}
				parserContext.getRegistry().registerBeanDefinition(txAdvisorBeanName, advisorDef);

				// 将需要注册的bean封装到CompositeComponentDefinition中，并且进行注册
				CompositeComponentDefinition compositeDef = new CompositeComponentDefinition(element.getTagName(), eleSource);
				compositeDef.addNestedComponent(new BeanComponentDefinition(sourceDef, sourceName));
				compositeDef.addNestedComponent(new BeanComponentDefinition(interceptorDef, interceptorName));
				compositeDef.addNestedComponent(new BeanComponentDefinition(advisorDef, txAdvisorBeanName));
				parserContext.registerComponent(compositeDef);
			}
		}
	}

}
