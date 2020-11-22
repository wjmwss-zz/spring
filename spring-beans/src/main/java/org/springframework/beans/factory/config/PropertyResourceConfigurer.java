/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.support.PropertiesLoaderSupport;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Allows for configuration of individual bean property values from a property resource,
 * i.e. a properties file. Useful for custom config files targeted at system
 * administrators that override bean properties configured in the application context.
 *
 * <p>Two concrete implementations are provided in the distribution:
 * <ul>
 * <li>{@link PropertyOverrideConfigurer} for "beanName.property=value" style overriding
 * (<i>pushing</i> values from a properties file into bean definitions)
 * <li>{@link PropertyPlaceholderConfigurer} for replacing "${...}" placeholders
 * (<i>pulling</i> values from a properties file into bean definitions)
 * </ul>
 *
 * <p>Property values can be converted after reading them in, through overriding
 * the {@link #convertPropertyValue} method. For example, encrypted values
 * can be detected and decrypted accordingly before processing them.
 *
 * @author Juergen Hoeller
 * @see PropertyOverrideConfigurer
 * @see PropertyPlaceholderConfigurer
 * @since 02.10.2003
 */
public abstract class PropertyResourceConfigurer extends PropertiesLoaderSupport implements BeanFactoryPostProcessor, PriorityOrdered {

	// default: same as non-Ordered
	private int order = Ordered.LOWEST_PRECEDENCE;

	/**
	 * Set the order value of this object for sorting purposes.
	 *
	 * @see PriorityOrdered
	 */
	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * {@linkplain #mergeProperties Merge}, {@linkplain #convertProperties convert} and
	 * {@linkplain #processProperties process} properties against the given bean factory.
	 *
	 * @throws BeanInitializationException if any properties cannot be loaded
	 *                                     <p>
	 *                                     PropertyPlaceholderConfigurer 允许我们用 Properties 文件中的属性，来定义应用上下文（配置文件或者注解）。
	 *                                     就是说我们在 XML 配置文件（或者其他方式，如注解方式）中使用占位符的方式来定义一些资源，
	 *                                     并将这些占位符所代表的资源配置到 Properties 中，这样只需要对 Properties 文件进行修改即可，这个特性非常重要，在项目中可以灵活配置。
	 *                                     <p>
	 *                                     这个函数，其实就是实现了 BeanFactoryPostProcessor 接口，重写的 postProcessBeanFactory 函数
	 *                                     BeanFactoryPostProcessor 接口看印象笔记中《Spring深入分析各大类》下的《BeanPostProcessor接口》
	 *                                     <p>
	 *                                     PropertyResourceConfigurer.java，该类为属性资源的配置类，它实现了 BeanFactoryPostProcessor 接口
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		try {
			// <1> 返回合并的 Properties 实例
			Properties mergedProps = mergeProperties();

			// Convert the merged properties, if necessary.
			// <2> 转换合并属性
			convertProperties(mergedProps);

			// Let the subclass process the properties.
			// <3> 子类处理，具体解析见函数体内
			processProperties(beanFactory, mergedProps);

			/**
			 * <1> 处，调用 #mergeProperties() 方法，返回合并的 Properties 实例。Properties 实例维护这一组 key-value ，其实就是 Properties 配置文件中的内容。
			 * <2> 处，调用 #convertProperties(Properties props) 方法，转换合并的值，其实就是将原始值替换为真正的值。
			 * <3> 处，调用 #processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) 方法，前面两个步骤已经将配置文件中的值进行了处理，那么该方法就是真正的替换过程，该方法由子类实现。
			 */
		} catch (IOException ex) {
			throw new BeanInitializationException("Could not load properties", ex);
		}
	}

	/**
	 * Convert the given merged properties, converting property values
	 * if necessary. The result will then be processed.
	 * <p>The default implementation will invoke {@link #convertPropertyValue}
	 * for each property value, replacing the original with the converted value.
	 *
	 * @param props the Properties to convert
	 * @see #processProperties
	 */
	protected void convertProperties(Properties props) {
		Enumeration<?> propertyNames = props.propertyNames();
		while (propertyNames.hasMoreElements()) {
			String propertyName = (String) propertyNames.nextElement();
			String propertyValue = props.getProperty(propertyName);
			String convertedValue = convertProperty(propertyName, propertyValue);
			if (!ObjectUtils.nullSafeEquals(propertyValue, convertedValue)) {
				props.setProperty(propertyName, convertedValue);
			}
		}
	}

	/**
	 * Convert the given property from the properties source to the value
	 * which should be applied.
	 * <p>The default implementation calls {@link #convertPropertyValue(String)}.
	 *
	 * @param propertyName  the name of the property that the value is defined for
	 * @param propertyValue the original value from the properties source
	 * @return the converted value, to be used for processing
	 * @see #convertPropertyValue(String)
	 */
	protected String convertProperty(String propertyName, String propertyValue) {
		return convertPropertyValue(propertyValue);
	}

	/**
	 * Convert the given property value from the properties source to the value
	 * which should be applied.
	 * <p>The default implementation simply returns the original value.
	 * Can be overridden in subclasses, for example to detect
	 * encrypted values and decrypt them accordingly.
	 *
	 * @param originalValue the original value from the properties source
	 *                      (properties file or local "properties")
	 * @return the converted value, to be used for processing
	 * @see #setProperties
	 * @see #setLocations
	 * @see #setLocation
	 * @see #convertProperty(String, String)
	 */
	protected String convertPropertyValue(String originalValue) {
		return originalValue;
	}

	/**
	 * Apply the given Properties to the given BeanFactory.
	 *
	 * @param beanFactory the BeanFactory used by the application context
	 * @param props       the Properties to apply
	 * @throws org.springframework.beans.BeansException in case of errors
	 *                                                  <p>
	 *                                                  1、调用 #mergeProperties() 方法，返回合并的 Properties 实例。Properties 实例维护这一组 key-value ，其实就是 Properties 配置文件中的内容。
	 *                                                  2、调用 #convertProperties(Properties props) 方法，转换合并的值，其实就是将原始值替换为真正的值。
	 *                                                  3、调用 #processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) 方法，前面两个步骤已经将配置文件中的值进行了处理，那么该方法就是真正的替换过程，该方法由子类实现。
	 *                                                  <p>
	 *                                                  该函数，就是处理 Properties 配置文件的真正替换过程，由 PropertyPlaceholderConfigurer 子类实现
	 */
	protected abstract void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) throws BeansException;

}
