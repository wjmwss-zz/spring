/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.context;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.testfixture.beans.LifecycleBean;
import org.springframework.beans.testfixture.beans.TestBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.SimpleWebApplicationContext;
import org.springframework.web.testfixture.servlet.MockServletConfig;
import org.springframework.web.testfixture.servlet.MockServletContext;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.FileNotFoundException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link ContextLoader} and {@link ContextLoaderListener}.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Chris Beams
 * @see org.springframework.web.context.support.Spr8510Tests
 * @since 12.08.2003
 */
public class ContextLoaderTests {

	/**
	 * 概述：“熟悉”的 web.xml 配置文件中：
	 * [1] org.springframework.web.context.ContextLoaderListener 类：
	 * 配置了 org.springframework.web.context.ContextLoaderListener 对象。
	 * 这是一个 javax.servlet.ServletContextListener 对象，会初始化一个 【Root】 Spring WebApplicationContext 容器。
	 *
	 * [2] org.springframework.web.servlet.DispatcherServlet 类
	 * 配置了 org.springframework.web.servlet.DispatcherServlet 对象。
	 * 这是一个 javax.servlet.http.HttpServlet 对象，它除了拦截我们制定的 *.do 请求外，
	 * 也会初始化一个属于它的 Spring WebApplicationContext 容器。并且，这个容器是以上面的【Root】容器作为父容器
	 *
	 * 为什么有了 [2] 创建了容器，还需要 [1] 创建了容器呢？因为可以配置多个 [2] 。当然，实际场景下，不太会配置多个 [2]
	 * 再总结一次，[1] 和 [2] 分别会创建其对应的 Spring WebApplicationContext 容器，并且它们是父子容器的关系。
	 */

	/**
	 *
	 */
	@Test
	public void testContextLoaderListenerWithDefaultContext() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"/org/springframework/web/context/WEB-INF/applicationContext.xml " +
						"/org/springframework/web/context/WEB-INF/context-addition.xml");

		/**
		 * 在概述中，Root WebApplicationContext 容器的初始化，是通过 ContextLoaderListener 来实现。
		 * 在 Servlet 容器启动时，例如 Tomcat、Jetty 启动，则会被 ContextLoaderListener 监听到，
		 * 从而调用 #contextInitialized(ServletContextEvent event) 方法，初始化 Root WebApplicationContext 容器。
		 *
		 * org.springframework.web.context.ContextLoaderListener ，
		 * 实现 ServletContextListener 接口，继承 ContextLoader 类，
		 * 实现 Servlet 容器启动和关闭时，分别初始化和销毁 WebApplicationContext 容器。
		 *
		 * 注意，这个 ContextLoaderListener 类，是在 spring-web 项目中。
		 *
		 * 之所以有两个构造方法，是因为父类 ContextLoader 有这两个构造方法，所以必须重新定义。
		 * 比较需要注意的是，第二个构造方法，可以直接传递一个 WebApplicationContext 对象， 那样，实际 ContextLoaderListener 就无需在创建一个新的 WebApplicationContext 对象
		 */
		ServletContextListener listener = new ContextLoaderListener();
		ServletContextEvent event = new ServletContextEvent(sc);
		// 由 ContextLoaderListener 实现， 初始化 WebApplicationContext，具体解析见函数体内
		listener.contextInitialized(event);
		// 看完上面看文章：《Spring MVC 原理探秘 - 容器的创建过程》 https://www.tianxiaobo.com/2018/06/30/Spring-MVC-%E5%8E%9F%E7%90%86%E6%8E%A2%E7%A7%98-%E5%AE%B9%E5%99%A8%E7%9A%84%E5%88%9B%E5%BB%BA%E8%BF%87%E7%A8%8B/ */


		String contextAttr = WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE;
		WebApplicationContext context = (WebApplicationContext) sc.getAttribute(contextAttr);
		boolean condition1 = context instanceof XmlWebApplicationContext;
		assertThat(condition1).as("Correct WebApplicationContext exposed in ServletContext").isTrue();
		assertThat(WebApplicationContextUtils.getRequiredWebApplicationContext(sc) instanceof XmlWebApplicationContext).isTrue();
		LifecycleBean lb = (LifecycleBean) context.getBean("lifecycle");
		assertThat(context.containsBean("father")).as("Has father").isTrue();
		assertThat(context.containsBean("rod")).as("Has rod").isTrue();
		assertThat(context.containsBean("kerry")).as("Has kerry").isTrue();
		boolean condition = !lb.isDestroyed();
		assertThat(condition).as("Not destroyed").isTrue();
		assertThat(context.containsBean("beans1.bean1")).isFalse();
		assertThat(context.containsBean("beans1.bean2")).isFalse();
		listener.contextDestroyed(event);
		assertThat(lb.isDestroyed()).as("Destroyed").isTrue();
		assertThat(sc.getAttribute(contextAttr)).isNull();
		assertThat(WebApplicationContextUtils.getWebApplicationContext(sc)).isNull();
	}

	/**
	 * Addresses the issues raised in <a
	 * href="https://opensource.atlassian.com/projects/spring/browse/SPR-4008"
	 * target="_blank">SPR-4008</a>: <em>Supply an opportunity to customize
	 * context before calling refresh in ContextLoaders</em>.
	 */
	@Test
	public void testContextLoaderListenerWithCustomizedContextLoader() {
		final StringBuffer buffer = new StringBuffer();
		final String expectedContents = "customizeContext() was called";
		final MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"/org/springframework/web/context/WEB-INF/applicationContext.xml");
		ServletContextListener listener = new ContextLoaderListener() {
			@Override
			protected void customizeContext(ServletContext sc, ConfigurableWebApplicationContext wac) {
				assertThat(sc).as("The ServletContext should not be null.").isNotNull();
				assertThat(sc).as("Verifying that we received the expected ServletContext.").isEqualTo(sc);
				assertThat(wac.isActive()).as("The ApplicationContext should not yet have been refreshed.").isFalse();
				buffer.append(expectedContents);
			}
		};
		listener.contextInitialized(new ServletContextEvent(sc));
		assertThat(buffer.toString()).as("customizeContext() should have been called.").isEqualTo(expectedContents);
	}

	@Test
	public void testContextLoaderListenerWithLocalContextInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		sc.addInitParameter(ContextLoader.CONTEXT_INITIALIZER_CLASSES_PARAM, StringUtils.arrayToCommaDelimitedString(
				new Object[]{TestContextInitializer.class.getName(), TestWebContextInitializer.class.getName()}));
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testContextLoaderListenerWithGlobalContextInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		sc.addInitParameter(ContextLoader.GLOBAL_INITIALIZER_CLASSES_PARAM, StringUtils.arrayToCommaDelimitedString(
				new Object[]{TestContextInitializer.class.getName(), TestWebContextInitializer.class.getName()}));
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testContextLoaderListenerWithMixedContextInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		sc.addInitParameter(ContextLoader.CONTEXT_INITIALIZER_CLASSES_PARAM, TestContextInitializer.class.getName());
		sc.addInitParameter(ContextLoader.GLOBAL_INITIALIZER_CLASSES_PARAM, TestWebContextInitializer.class.getName());
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testContextLoaderListenerWithProgrammaticInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.setContextInitializers(new TestContextInitializer(), new TestWebContextInitializer());
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testContextLoaderListenerWithProgrammaticAndLocalInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		sc.addInitParameter(ContextLoader.CONTEXT_INITIALIZER_CLASSES_PARAM, TestContextInitializer.class.getName());
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.setContextInitializers(new TestWebContextInitializer());
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testContextLoaderListenerWithProgrammaticAndGlobalInitializers() {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"org/springframework/web/context/WEB-INF/ContextLoaderTests-acc-context.xml");
		sc.addInitParameter(ContextLoader.GLOBAL_INITIALIZER_CLASSES_PARAM, TestWebContextInitializer.class.getName());
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.setContextInitializers(new TestContextInitializer());
		listener.contextInitialized(new ServletContextEvent(sc));
		WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(sc);
		TestBean testBean = wac.getBean(TestBean.class);
		assertThat(testBean.getName()).isEqualTo("testName");
		assertThat(wac.getServletContext().getAttribute("initialized")).isNotNull();
	}

	@Test
	public void testRegisteredContextInitializerCanAccessServletContextParamsViaEnvironment() {
		MockServletContext sc = new MockServletContext("");
		// config file doesn't matter - just a placeholder
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"/org/springframework/web/context/WEB-INF/empty-context.xml");

		sc.addInitParameter("someProperty", "someValue");
		sc.addInitParameter(ContextLoader.CONTEXT_INITIALIZER_CLASSES_PARAM,
				EnvApplicationContextInitializer.class.getName());
		ContextLoaderListener listener = new ContextLoaderListener();
		listener.contextInitialized(new ServletContextEvent(sc));
	}

	@Test
	public void testContextLoaderListenerWithUnknownContextInitializer() {
		MockServletContext sc = new MockServletContext("");
		// config file doesn't matter.  just a placeholder
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM,
				"/org/springframework/web/context/WEB-INF/empty-context.xml");
		sc.addInitParameter(ContextLoader.CONTEXT_INITIALIZER_CLASSES_PARAM,
				StringUtils.arrayToCommaDelimitedString(new Object[]{UnknownContextInitializer.class.getName()}));
		ContextLoaderListener listener = new ContextLoaderListener();
		assertThatExceptionOfType(ApplicationContextException.class).isThrownBy(() ->
				listener.contextInitialized(new ServletContextEvent(sc)))
				.withMessageContaining("not assignable");
	}

	@Test
	public void testContextLoaderWithCustomContext() throws Exception {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONTEXT_CLASS_PARAM,
				"org.springframework.web.servlet.SimpleWebApplicationContext");
		ServletContextListener listener = new ContextLoaderListener();
		ServletContextEvent event = new ServletContextEvent(sc);
		listener.contextInitialized(event);
		String contextAttr = WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE;
		WebApplicationContext wc = (WebApplicationContext) sc.getAttribute(contextAttr);
		boolean condition = wc instanceof SimpleWebApplicationContext;
		assertThat(condition).as("Correct WebApplicationContext exposed in ServletContext").isTrue();
	}

	@Test
	public void testContextLoaderWithInvalidLocation() throws Exception {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONFIG_LOCATION_PARAM, "/WEB-INF/myContext.xml");
		ServletContextListener listener = new ContextLoaderListener();
		ServletContextEvent event = new ServletContextEvent(sc);
		assertThatExceptionOfType(BeanDefinitionStoreException.class).isThrownBy(() ->
				listener.contextInitialized(event))
				.withCauseInstanceOf(FileNotFoundException.class);
	}

	@Test
	public void testContextLoaderWithInvalidContext() throws Exception {
		MockServletContext sc = new MockServletContext("");
		sc.addInitParameter(ContextLoader.CONTEXT_CLASS_PARAM,
				"org.springframework.web.context.support.InvalidWebApplicationContext");
		ServletContextListener listener = new ContextLoaderListener();
		ServletContextEvent event = new ServletContextEvent(sc);
		assertThatExceptionOfType(ApplicationContextException.class).isThrownBy(() ->
				listener.contextInitialized(event))
				.withCauseInstanceOf(ClassNotFoundException.class);
	}

	@Test
	public void testContextLoaderWithDefaultLocation() throws Exception {
		MockServletContext sc = new MockServletContext("");
		ServletContextListener listener = new ContextLoaderListener();
		ServletContextEvent event = new ServletContextEvent(sc);
		assertThatExceptionOfType(BeanDefinitionStoreException.class)
				.isThrownBy(() -> listener.contextInitialized(event))
				.havingCause()
				.isInstanceOf(IOException.class)
				.withMessageContaining("/WEB-INF/applicationContext.xml");
	}

	@Test
	public void testFrameworkServletWithDefaultLocation() throws Exception {
		DispatcherServlet servlet = new DispatcherServlet();
		servlet.setContextClass(XmlWebApplicationContext.class);
		assertThatExceptionOfType(BeanDefinitionStoreException.class)
				.isThrownBy(() -> servlet.init(new MockServletConfig(new MockServletContext(""), "test")))
				.havingCause()
				.isInstanceOf(IOException.class)
				.withMessageContaining("/WEB-INF/test-servlet.xml");
	}

	@Test
	public void testFrameworkServletWithCustomLocation() throws Exception {
		DispatcherServlet servlet = new DispatcherServlet();
		servlet.setContextConfigLocation("/org/springframework/web/context/WEB-INF/testNamespace.xml "
				+ "/org/springframework/web/context/WEB-INF/context-addition.xml");
		servlet.init(new MockServletConfig(new MockServletContext(""), "test"));
		assertThat(servlet.getWebApplicationContext().containsBean("kerry")).isTrue();
		assertThat(servlet.getWebApplicationContext().containsBean("kerryX")).isTrue();
	}

	@Test
	@SuppressWarnings("resource")
	public void testClassPathXmlApplicationContext() throws IOException {
		ApplicationContext context = new ClassPathXmlApplicationContext(
				"/org/springframework/web/context/WEB-INF/applicationContext.xml");
		assertThat(context.containsBean("father")).as("Has father").isTrue();
		assertThat(context.containsBean("rod")).as("Has rod").isTrue();
		assertThat(context.containsBean("kerry")).as("Hasn't kerry").isFalse();
		assertThat(((TestBean) context.getBean("rod")).getSpouse() == null).as("Doesn't have spouse").isTrue();
		assertThat("Roderick".equals(((TestBean) context.getBean("rod")).getName())).as("myinit not evaluated").isTrue();

		context = new ClassPathXmlApplicationContext(new String[]{
				"/org/springframework/web/context/WEB-INF/applicationContext.xml",
				"/org/springframework/web/context/WEB-INF/context-addition.xml"});
		assertThat(context.containsBean("father")).as("Has father").isTrue();
		assertThat(context.containsBean("rod")).as("Has rod").isTrue();
		assertThat(context.containsBean("kerry")).as("Has kerry").isTrue();
	}

	@Test
	@SuppressWarnings("resource")
	public void testSingletonDestructionOnStartupFailure() throws IOException {
		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(() ->
				new ClassPathXmlApplicationContext(new String[]{
						"/org/springframework/web/context/WEB-INF/applicationContext.xml",
						"/org/springframework/web/context/WEB-INF/fail.xml"}) {

					@Override
					public void refresh() throws BeansException {
						try {
							super.refresh();
						} catch (BeanCreationException ex) {
							DefaultListableBeanFactory factory = (DefaultListableBeanFactory) getBeanFactory();
							assertThat(factory.getSingletonCount()).isEqualTo(0);
							throw ex;
						}
					}
				});
	}

	private static class TestContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			ConfigurableEnvironment environment = applicationContext.getEnvironment();
			environment.getPropertySources().addFirst(new PropertySource<Object>("testPropertySource") {
				@Override
				public Object getProperty(String key) {
					return "name".equals(key) ? "testName" : null;
				}
			});
		}
	}

	private static class TestWebContextInitializer implements
			ApplicationContextInitializer<ConfigurableWebApplicationContext> {

		@Override
		public void initialize(ConfigurableWebApplicationContext applicationContext) {
			ServletContext ctx = applicationContext.getServletContext(); // type-safe access to servlet-specific methods
			ctx.setAttribute("initialized", true);
		}
	}

	private static class EnvApplicationContextInitializer
			implements ApplicationContextInitializer<ConfigurableWebApplicationContext> {

		@Override
		public void initialize(ConfigurableWebApplicationContext applicationContext) {
			// test that ApplicationContextInitializers can access ServletContext properties
			// via the environment (SPR-8991)
			String value = applicationContext.getEnvironment().getRequiredProperty("someProperty");
			assertThat(value).isEqualTo("someValue");
		}
	}

	private static interface UnknownApplicationContext extends ConfigurableApplicationContext {

		void unheardOf();
	}

	private static class UnknownContextInitializer implements ApplicationContextInitializer<UnknownApplicationContext> {

		@Override
		public void initialize(UnknownApplicationContext applicationContext) {
			applicationContext.unheardOf();
		}
	}

}
