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

package org.springframework.beans.factory.support;

import org.apache.commons.logging.Log;
import org.springframework.beans.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.core.*;
import org.springframework.lang.Nullable;
import org.springframework.util.*;
import org.springframework.util.ReflectionUtils.MethodCallback;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 * @since 13.02.2004
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/**
	 * Whether this environment lives within a native image.
	 * Exposed as a private static field rather than in a {@code NativeImageDetector.inNativeImage()} static method due to https://github.com/oracle/graal/issues/2594.
	 *
	 * @see <a href="https://github.com/oracle/graal/blob/master/sdk/src/org.graalvm.nativeimage/src/org/graalvm/nativeimage/ImageInfo.java">ImageInfo.java</a>
	 */
	private static final boolean IN_NATIVE_IMAGE = (System.getProperty("org.graalvm.nativeimage.imagecode") != null);

	/**
	 * Strategy for creating bean instances.
	 */
	private InstantiationStrategy instantiationStrategy;

	/**
	 * Resolver strategy for method parameter names.
	 */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/**
	 * Whether to automatically try to resolve circular references between beans.
	 */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper.
	 */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/**
	 * Cache of candidate factory methods per factory class.
	 */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/**
	 * Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array.
	 */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();

	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		if (IN_NATIVE_IMAGE) {
			this.instantiationStrategy = new SimpleInstantiationStrategy();
		} else {
			this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
		}
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 *
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}

	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 *
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 *
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 *
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}

	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}

	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		} else {
			Object bean;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(bd, null, this),
						getAccessControlContext());
			} else {
				bean = getInstantiationStrategy().instantiate(bd, null, this);
			}
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	/**
	 * BeanPostProcessor 在前面介绍 bean 加载的过程曾多次遇到，这是 Spring 中开放式框架中必不可少的一个亮点。
	 * BeanPostProcessor 的作用是：如果我们想要在 Spring 容器完成 Bean 的实例化，配置和其他的初始化后添加一些自己的逻辑处理，那么请使用该接口，
	 * 这个接口给与了用户充足的权限去更改或者扩展 Spring，是我们对 Spring 进行扩展和增强处理一个必不可少的接口。
	 * <p>
	 * 逻辑就是通过 #getBeanPostProcessors() 方法，获取定义的 BeanPostProcessor ，
	 * 然后分别调用其 #postProcessBeforeInitialization(...)、#postProcessAfterInitialization(...) 方法，进行自定义的业务处理。
	 *
	 * @param existingBean the existing bean instance
	 * @param beanName     the name of the bean, to be passed to it if necessary
	 *                     (only passed to {@link BeanPostProcessor BeanPostProcessors};
	 *                     can follow the {@link #ORIGINAL_INSTANCE_SUFFIX} convention in order to
	 *                     enforce the given instance to be returned, i.e. no proxies etc)
	 * @return
	 * @throws BeansException
	 */
	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) throws BeansException {
		Object result = existingBean;
		// 遍历 BeanPostProcessor 数组
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 处理
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			// 返回空，则返回 result
			if (current == null) {
				return result;
			}
			// 修改 result
			result = current;
		}
		return result;
	}

	/**
	 * BeanPostProcessor 在前面介绍 bean 加载的过程曾多次遇到，这是 Spring 中开放式框架中必不可少的一个亮点。
	 * BeanPostProcessor 的作用是：如果我们想要在 Spring 容器完成 Bean 的实例化，配置和其他的初始化后添加一些自己的逻辑处理，那么请使用该接口，
	 * 这个接口给与了用户充足的权限去更改或者扩展 Spring，是我们对 Spring 进行扩展和增强处理一个必不可少的接口。
	 * <p>
	 * 逻辑就是通过 #getBeanPostProcessors() 方法，获取定义的 BeanPostProcessor ，
	 * 然后分别调用其 #postProcessBeforeInitialization(...)、#postProcessAfterInitialization(...) 方法，进行自定义的业务处理。
	 *
	 * @param existingBean the existing bean instance
	 * @param beanName     the name of the bean, to be passed to it if necessary
	 *                     (only passed to {@link BeanPostProcessor BeanPostProcessors};
	 *                     can follow the {@link #ORIGINAL_INSTANCE_SUFFIX} convention in order to
	 *                     enforce the given instance to be returned, i.e. no proxies etc)
	 * @return
	 * @throws BeansException
	 */
	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) throws BeansException {
		Object result = existingBean;
		// 遍历 BeanPostProcessor
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			// 处理
			Object current = processor.postProcessAfterInitialization(result, beanName);
			// 返回空，则返回 result
			if (current == null) {
				return result;
			}
			// 修改 result
			result = current;
		}
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(
				existingBean, getBeanPostProcessorCache().destructionAware, getAccessControlContext()).destroy();
	}

	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			return getBean(name, descriptor.getDependencyType());
		} finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}

	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 *
	 * @see #doCreateBean
	 * <p>
	 * 该方法定义在 AbstractBeanFactory 中，由AbstractAutowireCapableBeanFactory默认实现，其含义是根据给定的 BeanDefinition 和 args 实例化一个 Bean 对象。
	 * 如果该 BeanDefinition 存在父类，则该 BeanDefinition 已经合并了父类的属性。
	 * 非常重要：所有 Bean 实例的创建，都会委托给该方法实现。
	 * 该方法接受三个方法参数：
	 * beanName ：bean 的名字。
	 * mbd ：已经合并了父类属性的（如果有的话）BeanDefinition 对象。
	 * args ：用于构造函数或者工厂方法创建 Bean 实例对象的参数。
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) throws BeanCreationException {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		// <1> 解析指定 BeanDefinition 的 class 属性，确保此时的 bean 已经被解析了，具体解析见函数体内
		// 主要是解析 bean definition 的 class 类，并将已经解析的 Class 存储在 bean definition 中以供后面使用。
		// 如果解析的 class 不为空，则会将该 BeanDefinition 进行设置到 mbdToUse 中。这样做的主要目的是，因为动态解析的 class 无法保存到共享的 BeanDefinition 中。
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		try {
			// <2> 验证和准备覆盖方法，处理 override 属性（其实就是一个验证是否存在重载方法，并做了一个小优化而已，没有什么实质性的处理），具体解析见函数体内
			mbdToUse.prepareMethodOverrides();
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(), beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			/**
			 * <3> 实例化的前置处理，具体解析见函数体内
			 * 给 BeanPostProcessors 一个机会用来返回一个代理类而不是真正的类实例
			 * AOP 的功能就是基于这个地方
			 * #resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) 方法的作用，是给 BeanPostProcessors 后置处理器返回一个代理对象的机会。
			 * 其实在调用该方法之前 Spring 一直都没有创建 bean ，那么这里返回一个 bean 的代理类有什么作用呢？作用体现在后面的 if 判断，代码如下：
			 */
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);//具体解析见函数体内
			//如果代理对象不为空，则直接返回代理对象，这一步骤有非常重要的作用，Spring 后续实现 AOP 就是基于这个地方判断的。
			if (bean != null) {
				return bean;
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName, "BeanPostProcessor before instantiation of bean failed", ex);
		}

		try {
			// <4> 如果没有代理对象，就只能走常规的路线进行 bean 的创建了，具体解析见函数体内
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		} catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		} catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the merged bean definition for the bean
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * <p>
	 * 创建bean对象（常规路线）
	 * <p>
	 * 这里先对该函数做个汇总：
	 * #doCreateBean(...) 方法，主要用于完成 bean 的创建和初始化工作，我们可以将其分为四个过程：
	 * <p>
	 * 1、#createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，实例化 bean 。【又长又臭的代码，四种策略实例化bean】
	 * 2、循环依赖的处理。
	 * 3、#populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) 方法，进行属性填充。
	 * 4、#initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) 方法，初始化 Bean 。
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) throws BeanCreationException {
		// Instantiate the bean.
		// BeanWrapper 是对 Bean 的包装，其接口中所定义的功能很简单包括设置获取被包装的对象，获取被包装 bean 的属性描述器
		BeanWrapper instanceWrapper = null;
		// <1> 是单例模式，则从未完成的 FactoryBean 缓存中删除
		if (mbd.isSingleton()) {
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		// <2> 使用合适的实例化策略来创建新的实例，策略有：工厂方法、构造函数自动注入、简单初始化，具体解析见函数体内，主要是将 BeanDefinition 转换为 org.springframework.beans.BeanWrapper 对象。
		// 具体解析见函数体内【四种策略实例化bean，又长又臭的代码疯狂预警】（第一步）
		if (instanceWrapper == null) {
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		// 包装的实例对象
		Object bean = instanceWrapper.getWrappedInstance();
		// 包装的实例对象的类型
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		// <3> 判断是否有后置处理
		// 如果有后置处理，则允许后置处理修改 BeanDefinition
		synchronized (mbd.postProcessingLock) {
			if (!mbd.postProcessed) {
				try {
					// 后置处理修改 BeanDefinition
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Post-processing of merged bean definition failed", ex);
				}
				mbd.postProcessed = true;
			}
		}

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		// <4> 解决单例模式的循环依赖
		boolean earlySingletonExposure =
				(
						mbd.isSingleton()// 单例模式
								&& this.allowCircularReferences// 允许循环依赖
								&& isSingletonCurrentlyInCreation(beanName)// 当前单例 bean 是否正在被创建
				);
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName + "' to allow for resolving potential circular references");
			}
			// 提前将创建的 bean 实例加入到 singletonFactories 中
			// 这里是为了后期避免循环依赖
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		// 开始初始化 bean 实例对象
		Object exposedObject = bean;
		try {
			// <5> 对 bean 进行填充，将各个属性值注入，其中，可能存在依赖于其他 bean 的属性，则会递归初始依赖 bean，具体解析见函数体内（第二步）
			populateBean(beanName, mbd, instanceWrapper);
			// <6> 调用初始化方法，具体解析见函数体内（最后一步）
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		} catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			} else {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		// <7> 循环依赖处理
		if (earlySingletonExposure) {
			// 获取 earlySingletonReference
			Object earlySingletonReference = getSingleton(beanName, false);
			// 只有在存在循环依赖的情况下，earlySingletonReference 才不会为空
			if (earlySingletonReference != null) {
				// 如果 exposedObject 没有在初始化方法中被改变，也就是没有被增强
				if (exposedObject == bean) {
					exposedObject = earlySingletonReference;
					// 处理依赖
				} else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName, "Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) + "] in its raw version as part of a circular reference, but has eventually been " + "wrapped. This means that said other beans do not use the final version of the " + "bean. This is often the result of over-eager type matching - consider using " + "'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		// <8> 注册 bean
		try {
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					return predicted;
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 *
	 * @param beanName     the name of the bean (for error handling purposes)
	 * @param mbd          the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			targetType = (mbd.getFactoryMethodName() != null ?
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 *
	 * @param beanName     the name of the bean (for error handling purposes)
	 * @param mbd          the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			} else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				return null;
			}
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						} catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					} else {
						uniqueCandidate = (commonType == null ? candidate : null);
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		mbd.factoryMethodReturnType = cachedReturnType;
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				} else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 *
	 * @param beanClass         the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	@Deprecated
	@Nullable
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 *
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd      the merged bean definition for the bean
	 * @param bean     the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
			}
		}
		return exposedObject;
	}

	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			} catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			} catch (BeanCreationException ex) {
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			} finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		} catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		} catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		} finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 *
	 * @param mbd      the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 * <p>
	 * 在bean初始化前创建代理对象，是AOP的功能，这个方法核心就在于 applyBeanPostProcessorsBeforeInstantiation() 和 applyBeanPostProcessorsAfterInitialization() 两个方法，
	 * before 为实例化前的后处理器应用，after 为实例化后的后处理器应用。
	 * 由于本文的主题是创建 bean ，关于 Bean 的增强处理后续 后续再详细说明。
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					// 前置
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					if (bean != null) {
						// 后置
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 *
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName  the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
			Object result = bp.postProcessBeforeInstantiation(beanClass, beanName);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 * <p>
	 * 关于加载bean，在 #doCreateBean(...) 方法，主要用于完成 bean 的创建和初始化工作，我们可以将其分为四个过程：
	 * <p>
	 * 1、#createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，实例化 bean 。【又长又臭的代码，四种策略实例化bean】
	 * 2、循环依赖的处理。
	 * 3、#populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) 方法，进行属性填充。
	 * 4、#initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) 方法，初始化 Bean 。
	 * <p>
	 * 此处为#createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，实例化 bean 。【又长又臭的代码，四种策略实例化bean】
	 * <p>
	 * 介绍 #createBeanInstance(...) 函数：实例化bean对象
	 * Spring中的自动装配模式实例化Bean：
	 * 使用合适的实例化策略来创建新的实例，策略有：工厂方法、构造函数自动注入、简单初始化（默认构造函数注入），具体解析见函数体内，主要是将 BeanDefinition 转换为 org.springframework.beans.BeanWrapper 对象。
	 * 一共有以下：
	 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
	 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		// 解析 bean ，将 bean 类名解析为 class 引用。
		Class<?> beanClass = resolveBeanClass(mbd, beanName);
		// 如果不是 public 的 bean 类，抛出异常
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		/**
		 * <1> 如果存在 Supplier 回调，则使用给定的回调方法初始化策略
		 * 首先,从 BeanDefinition 中获取 Supplier 对象。如果不为空，则调用 #obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法，
		 *
		 * 先看下java8内置的函数式接口
		 * >* 四大核心函数式接口：
		 * >   * Consumer<T>    : 消费型接口（有去无回）void accept(T t);
		 * >   * Supplier<T>    : 供给型接口（无去有回） T get();
		 * >   * Function<T, R> : 函数型接口（有去有回）R apply(T t);
		 * >   * Predicate<T>   : 断言型接口 boolean test(T t);
		 * >* 对应的增强型：
		 * >   * BiConsumer<T>    : 消费型接口（有去无回）void accept(T t, U u);
		 * >   * BiFunction<T, R> : 函数型接口（有去有回）R apply(T t, U u);
		 * >   * BiPredicate<T>   : 断言型接口 boolean test(T t, U u);
		 *
		 * Supplier 接口仅有一个功能性的 #get() 方法，该方法会返回一个 <T> 类型的对象，有点儿类似工厂方法。
		 * Supplier 接口一般是用来创建对象的，类似工厂方法
		 * 这个接口有什么作用？用于指定创建 bean 的回调。如果我们设置了这样的回调，那么其他的构造器或者工厂方法都会没有用。
		 * 在什么时候设置该 Supplier 参数呢？Spring 提供了相应的 setter 方法，如下：
		 * // AbstractBeanDefinition.java
		 * // 创建 Bean 的 Supplier 对象
		 * \@Nullable
		 * private Supplier<?> instanceSupplier;
		 *
		 * public void setInstanceSupplier (@Nullable Supplier<?> instanceSupplier){
		 *	  this.instanceSupplier = instanceSupplier;
		 * }
		 *
		 * 在构造 BeanDefinition 对象的时候，设置了 instanceSupplier 该值，代码如下（以 RootBeanDefinition 为例）：
		 * // RootBeanDefinition.java
		 * public <T> RootBeanDefinition(@Nullable Class<T> beanClass, String scope, @Nullable Supplier<T> instanceSupplier) {
		 *    super();
		 * 	  setBeanClass(beanClass);
		 * 	  setScope(scope);
		 * 	  // 设置 instanceSupplier 属性
		 * 	  setInstanceSupplier(instanceSupplier);
		 * }
		 *
		 * 如果设置了 instanceSupplier 属性，则可以调用 #obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) 方法，完成 Bean 的初始化。
		 * 具体解析见函数体内
		 */
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		if (instanceSupplier != null) {
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		// 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
		// <2> 如果是工厂，则使用 FactoryBean 的 factory-method 来创建，支持静态工厂和实例工厂，具体解析见函数体内【预警：代码量巨大，且动态工厂和静态工厂并不常用，关于动态、静态工厂，具体可以看下印象笔记spring源码剪藏里面的静态工厂和动态工厂的区别】
		if (mbd.getFactoryMethodName() != null) {
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		/**
		 * 这里做个汇总：
		 * #createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，用于实例化 Bean 对象。它会根据不同情况，选择不同的实例化策略来完成 Bean 的初始化，主要包括：
		 *
		 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
		 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
		 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
		 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
		 *
		 * 上面已经分析了前两种 Supplier 回调和工厂方法初始化。现在看后两种构造函数注入。
		 */

		// Shortcut when re-creating the same bean...
		// <3> 首先判断缓存，如果缓存中存在，即已经解析过了，则直接使用已经解析了的。根据 constructorArgumentsResolved 参数来判断：
		boolean resolved = false;
		boolean autowireNecessary = false;
		if (args == null) {
			// constructorArgumentLock 构造函数的常用锁
			synchronized (mbd.constructorArgumentLock) {
				// 如果已缓存的解析的构造函数或者工厂方法不为空，则可以利用构造函数解析：autowireConstructor(beanName, mbd, null, null)
				// 因为需要根据参数确认到底使用哪个构造函数，该过程比较消耗性能，所以采用缓存机制，这里也相当于是打个标记
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					resolved = true;
					//是否需要构造函数自动注入
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}

		// 已经解析好了，直接注入即可
		if (resolved) {
			// <3.1> autowire 自动注入，调用构造函数自动注入，具体解析见函数体内（这里会涉及到：反射创建 Bean 对象、CGLIB 创建 Bean 对象）
			if (autowireNecessary) {
				return autowireConstructor(beanName, mbd, null, null);
			} else {
				// <3.2> 使用默认构造函数构造，具体解析见函数体内（会涉及到反射创建、CGLIB创建）
				return instantiateBean(beanName, mbd);
			}
		}

		// Candidate constructors for autowiring?
		// <4> 确定解析的构造函数
		// 主要是检查已经注册的 SmartInstantiationAwareBeanPostProcessor
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		// <4.1> 有参数情况时，创建 Bean 。先利用参数个数，类型等，确定最精确匹配的构造方法。
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR || mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		// <4.1> 选择构造方法，创建 Bean 。（就是构造函数自动注入）（会涉及到反射创建、CGLIB创建）
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		// <4.2> 有参数时，又没获取到构造方法，则只能调用无参构造方法来创建实例了(兜底方法，也就是默认构造函数构造)（会涉及到反射创建、CGLIB创建）
		return instantiateBean(beanName, mbd);

		/**
		 * 关于反射创建以及CGLIB创建，其本质是：
		 * 在实例化的时候会根据是否有需要覆盖或者动态替换掉的方法，因为存在覆盖或者织入的话需要创建动态代理将方法织入，这个时候就只能选择 CGLIB 的方式来实例化，否则直接利用反射的方式即可，方便快捷。
		 */

		/**
		 * 做个总结：
		 * 对于 #createBeanInstance(...) 方法而言，他就是选择合适实例化策略来为 bean 创建实例对象，具体的策略有：
		 * Supplier 回调方式
		 * 工厂方法初始化
		 * 构造函数自动注入初始化
		 * 默认构造函数注入。
		 * 其中，工厂方法初始化和构造函数自动注入初始化两种方式最为复杂，主要是因为构造函数和构造参数的不确定性，Spring 需要花大量的精力来确定构造函数和构造参数，如果确定了则好办，直接选择实例化策略即可。
		 * 当然，在实例化的时候会根据是否有需要覆盖或者动态替换掉的方法，因为存在覆盖或者织入的话需要创建动态代理将方法织入，这个时候就只能选择 CGLIB 的方式来实例化，否则直接利用反射的方式即可，方便快捷。
		 */
	}

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 * <p>
	 * 当前线程，正在创建的 Bean 对象的名字
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/**
	 * Obtain a bean instance from the given supplier.
	 *
	 * @param instanceSupplier the configured supplier
	 * @param beanName         the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @see #getObjectForBeanInstance
	 * @since 5.0
	 * <p>
	 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
	 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
	 * <p>
	 * 该函数为：对设置了 instanceSupplier 的 BeanDefinition 进行初始化
	 */
	protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
		Object instance;
		// 获得原创建的 Bean 的对象名
		String outerBean = this.currentlyCreatedBean.get();
		// 设置新的 Bean 的对象名，到 currentlyCreatedBean 中
		this.currentlyCreatedBean.set(beanName);
		try {
			// <1> 调用 Supplier 的 get()，返回一个 Bean 对象
			instance = instanceSupplier.get();
		} finally {
			// 设置原创建的 Bean 的对象名，到 currentlyCreatedBean 中
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			} else {
				this.currentlyCreatedBean.remove();
			}
		}

		// 未创建 Bean 对象，则创建 NullBean 对象
		if (instance == null) {
			instance = new NullBean();
		}
		// <2> 然后，根据 instance 构造一个 BeanWrapper 对象 bw
		BeanWrapper bw = new BeanWrapperImpl(instance);
		// <3> 初始化 BeanWrapper 对象
		initBeanWrapper(bw);
		return bw;
		// 有关于 BeanWrapper ，后面再详细讲解
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 *
	 * @see #obtainFromSupplier
	 * @since 5.0
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 *
	 * @param beanClass the raw class of the bean
	 * @param beanName  the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				if (ctors != null) {
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 *
	 * <p>
	 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
	 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
	 * <p>
	 * 该函数为：使用默认构造函数构造 Bean 对象
	 * 这个方法,相比于 #instantiateUsingFactoryMethod(...) 、 #autowireConstructor(...) 方法，实在是太简单了，因为，它没有参数，所以不需要确认经过复杂的过来来确定构造器、构造参数
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			Object beanInstance;
			// 安全模式（spring一堆这种代码，是 JVM 权限的，不用太过在意）
			if (System.getSecurityManager() != null) {
				beanInstance = AccessController.doPrivileged(
						// 获得 InstantiationStrategy 对象，并使用它，创建 Bean 对象
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(mbd, beanName, this),
						getAccessControlContext());
			} else {
				// 获得 InstantiationStrategy 对象，并使用它，创建 Bean 对象（会涉及到反射创建、CGLIB创建）
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			}
			// 封装 BeanWrapperImpl  并完成初始化
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			initBeanWrapper(bw);
			return bw;
		} catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 * <p>
	 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
	 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
	 * <p>
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 该函数为：如果mbd是工厂，使用该方法完成 bean 的初始化工作
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		// 构造一个 ConstructorResolver 对象，然后调用其 #instantiateUsingFactoryMethod(EvaluationContext context, String typeName, List<TypeDescriptor> argumentTypes) 方法。
		// org.springframework.expression.ConstructorResolver 是构造方法或者工厂类初始化 bean 的委托类，具体解析见函数体内
		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the bean definition for the bean
	 * @param ctors        the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * <p>
	 * Supplier 回调：#obtainFromSupplier(final String beanName, final RootBeanDefinition mbd) 方法。
	 * 工厂方法初始化：#instantiateUsingFactoryMethod(String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) 方法。
	 * 构造函数自动注入初始化：#autowireConstructor(final String beanName, final RootBeanDefinition mbd, Constructor<?>[] chosenCtors, final Object[] explicitArgs) 方法。
	 * 默认构造函数注入：#instantiateBean(final String beanName, final RootBeanDefinition mbd) 方法。
	 * <p>
	 * 该函数为：构造函数自动注入初始化，通过带有参数的构造方法，来初始化 Bean 对象
	 */
	protected BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {
		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @param bw       the BeanWrapper with bean instance
	 *                 <p>
	 *                 关于加载bean，在 #doCreateBean(...) 方法，主要用于完成 bean 的创建和初始化工作，我们可以将其分为四个过程：
	 *                 <p>
	 *                 1、#createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，实例化 bean 。【又长又臭的代码，四种策略实例化bean】
	 *                 2、循环依赖的处理。
	 *                 3、#populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) 方法，进行属性填充。
	 *                 4、#initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) 方法，初始化 Bean 。
	 *                 <p>
	 *                 此处为#populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) 方法，进行属性填充：
	 *                 该函数的作用是将 BeanDefinition 中的属性值赋值给 BeanWrapper 实例对象
	 */
	@SuppressWarnings("deprecation")  // for postProcessPropertyValues
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		// 没有实例化对象
		if (bw == null) {
			// 有属性，则抛出 BeanCreationException 异常
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			} else {
				// 没有属性，直接 return 返回
				// Skip property population phase for null instance.
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		// <1> 在设置属性之前给 InstantiationAwareBeanPostProcessors 最后一次改变 bean 的机会
		// 根据 hasInstantiationAwareBeanPostProcessors 属性来判断，是否需要在注入属性之前给 InstantiationAwareBeanPostProcessors 最后一次改变 bean 的机会。此过程可以控制 Spring 是否继续进行属性填充。
		if (!mbd.isSynthetic() // bean 不是"合成"的，即未由应用程序本身定义
				&& hasInstantiationAwareBeanPostProcessors()) {// 是否持有 InstantiationAwareBeanPostProcessor
			// 迭代所有的 BeanPostProcessors
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 返回值为是否继续填充 bean
				// postProcessAfterInstantiation：如果应该在 bean上面设置属性则返回 true，否则返回 false
				// 一般情况下，应该是返回true 。
				// 返回 false 的话，将会阻止在此 Bean 实例上调用任何后续的 InstantiationAwareBeanPostProcessor 实例。
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					return;
				}
			}
		}

		// 统一存入到 PropertyValues 中，PropertyValues 用于描述 bean 的属性。

		// bean 的属性值
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

		// <2> 自动注入
		// 根据注入类型( AbstractBeanDefinition#getResolvedAutowireMode() 方法的返回值 )的不同来判断，具体解析见函数体内
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		// 是根据名称来自动注入（#autowireByName(...)）, 还是根据类型来自动注入（#autowireByType(...)）
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			// 将 PropertyValues 封装成 MutablePropertyValues 对象
			// MutablePropertyValues 允许对属性进行简单的操作，并提供构造函数以支持Map的深度复制和构造
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			// 根据属性名称，完成自动依赖注入，具体解析见函数体内
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			// 根据属性类型，完成自动依赖注入，具体解析见函数体内
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			pvs = newPvs;
		}

		/**
		 * 到这里就已经完成了所有属性的注入了。populateBean() 该方法就已经完成了一大半工作了：
		 * 下一步，则是对依赖 bean 的依赖检测和 PostProcessor 处理：#checkDependencies(beanName, mbd, filteredPds, pvs);
		 * 最后一步：#applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) 方法。
		 */

		// 是否已经注册了 InstantiationAwareBeanPostProcessors
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		// 是否需要进行【依赖检查】
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		// <3> 根据是否已经注册了 InstantiationAwareBeanPostProcessors 来进行 BeanPostProcessor 的处理
		PropertyDescriptor[] filteredPds = null;
		if (hasInstAwareBpps) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			// 遍历 BeanPostProcessor 数组
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 对所有需要依赖检查的属性进行后处理
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				if (pvsToUse == null) {
					// 从 bw 对象中提取 PropertyDescriptor 结果集
					// PropertyDescriptor：可以通过一对存取方法提取一个属性
					if (filteredPds == null) {
						filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
					}
					pvsToUse = bp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
					if (pvsToUse == null) {
						return;
					}
				}
				pvs = pvsToUse;
			}
		}
		// <4> 依赖检查
		if (needsDepCheck) {
			if (filteredPds == null) {
				filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			}
			// 依赖检查，对应 depends-on 属性，具体解析见函数体内（后续再看）
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		/**
		 * 上面只是完成了所有注入属性的获取，将获取的属性封装在 PropertyValues 的实例对象 pvs 中，并没有应用到已经实例化的 bean 中。
		 * 而 #applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) 方法，则是完成这一步骤的
		 */
		// <5> 将所有 PropertyValues 中的属性，填充到 BeanWrapper 中，具体见函数体内
		if (pvs != null) {
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 *
	 * @param beanName the name of the bean we're wiring up.
	 *                 Useful for debugging messages; not used functionally.
	 * @param mbd      bean definition to update through autowiring
	 * @param bw       the BeanWrapper from which we can obtain information about the bean
	 * @param pvs      the PropertyValues to register wired objects with
	 *                 <p>
	 *                 根据属性名称，完成自动依赖注入
	 */
	protected void autowireByName(String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
		// <1> 获取 Bean 对象中非简单属性，具体解析见函数体内
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		// 遍历 propertyName 数组
		for (String propertyName : propertyNames) {
			// 如果容器中包含指定名称的 bean，则将该 bean 注入到 bean中
			if (containsBean(propertyName)) {
				// 递归初始化相关 bean （这里又回到了最初的 getBean ）
				Object bean = getBean(propertyName);
				// 为指定名称的属性赋予属性值
				pvs.add(propertyName, bean);
				// 属性依赖注入，具体解析见函数体内
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName + "' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			} else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName + "' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 *
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd      the merged bean definition to update through autowiring
	 * @param bw       the BeanWrapper from which we can obtain information about the bean
	 * @param pvs      the PropertyValues to register wired objects with
	 *                 <p>
	 *                 根据属性类型，完成自动依赖注入
	 */
	protected void autowireByType(String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
		// 获取 TypeConverter 实例
		// 使用自定义的 TypeConverter，用于取代默认的 PropertyEditor 机制
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		// 获取非简单属性
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		// 遍历 propertyName 数组
		for (String propertyName : propertyNames) {
			try {
				// 获取 PropertyDescriptor 实例
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				// 不要尝试按类型
				if (Object.class != pd.getPropertyType()) {
					// 探测指定属性的 set 方法
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					// 解析指定 beanName 的属性所匹配的值，并把解析到的属性名称存储在 autowiredBeanNames （第三个参数） 中
					// 当属性存在过个封装 bean 时将会找到所有匹配的 bean 并将其注入，具体解析见函数体内
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					// 遍历 autowiredBeanName 数组
					for (String autowiredBeanName : autowiredBeanNames) {
						// 注册依赖的bean，具体解析见函数体内
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" + propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					// 清空 autowiredBeanName 数组
					autowiredBeanNames.clear();
				}
			} catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
		/**
		 * 其实主要过程和根据名称自动注入差不多，都是找到需要依赖注入的属性，
		 * 然后通过迭代的方式寻找所匹配的 bean，最后调用 #registerDependentBean(...) 方法，来注册依赖。
		 * 不过相对于 #autowireByName(...) 方法而言，根据类型寻找相匹配的 bean 过程比较复杂。
		 */
	}

	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 *
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw  the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 * <p>
	 * 获取 Bean 对象中非简单属性
	 * 该方法逻辑很简单，获取该 bean 的非简单属性。什么叫做非简单属性呢？
	 * 非简单属性，即类型为对象类型的属性，但是这里并不是将所有的对象类型都都会找到，比如 8 个原始类型，String 类型 ，Number类型、Date类型、URL类型、URI类型等都会被忽略
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		// 创建 result 集合
		Set<String> result = new TreeSet<>();
		PropertyValues pvs = mbd.getPropertyValues();
		// 遍历 PropertyDescriptor 数组
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null  // 有可写方法
					&& !isExcludedFromDependencyCheck(pd)  // 依赖检测中没有被忽略
					&& !pvs.contains(pd.getName()) // pvs 不包含该属性名
					&& !BeanUtils.isSimpleProperty(pd.getPropertyType())) // 不是简单属性类型
			{
				result.add(pd.getName()); // 添加到 result 中，其实这里获取的就是需要依赖注入的属性。
			}
		}
		/**
		 * 对上的总结：
		 * 过滤条件为：有可写方法、依赖检测中没有被忽略、不是简单属性类型。
		 * 过滤结果为：其实这里获取的就是需要依赖注入的属性。
		 */
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 *
	 * @param bw    the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 *
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 *
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the merged bean definition the bean was created with
	 * @param pds      the relevant property descriptors for the target bean
	 * @param pvs      the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 *
	 * @param beanName the bean name passed for better exception information
	 * @param mbd      the merged bean definition
	 * @param bw       the BeanWrapper wrapping the target object
	 * @param pvs      the new property values
	 *                 <p>
	 *                 将所有 PropertyValues 中的属性，填充到 BeanWrapper 中
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		if (pvs.isEmpty()) {
			return;
		}

		// 设置 BeanWrapperImpl 的 SecurityContext 属性
		if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
			((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
		}

		// MutablePropertyValues 类型属性
		MutablePropertyValues mpvs = null;
		// 原始类型
		List<PropertyValue> original;
		// 获得 original
		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			// 属性值已经转换
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					// 为实例化对象设置属性值 ，依赖注入真真正正地实现在此！！！！！
					bw.setPropertyValues(mpvs);
					return;
				} catch (BeansException ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			// 如果 pvs 不是 MutablePropertyValues 类型，则直接使用原始类型
			original = mpvs.getPropertyValueList();
		} else {
			original = Arrays.asList(pvs.getPropertyValues());
		}

		// 获取 TypeConverter = 获取用户自定义的类型转换
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		// 获取对应的解析器
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;
		// 遍历属性，将属性转换为对应类的对应属性的类型
		for (PropertyValue pv : original) {
			// 属性值不需要转换
			if (pv.isConverted()) {
				deepCopy.add(pv);
				// 属性值需要转换
			} else {
				String propertyName = pv.getName();
				// 原始的属性值，即转换之前的属性值
				Object originalValue = pv.getValue();
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				// 转换属性值，例如将引用转换为IoC容器中实例化对象引用 ！！！！！ 对属性值的解析！！
				// 该函数的具体解析：https://blog.csdn.net/Jack__Frost/article/details/70229593，第七点：「7. 追踪 resolveValueIfNecessary ，发现是在 BeanDefinitionValueResolver 类」 。
				// 已经剪藏在印象笔记【spring源码剪藏】里的文章【【精品文章】Spring应用、原理以及粗读源码系列（一）--框架总述、以Bean为核心的机制（IoC容器初始化以及依赖注入）】中的第七点：「7. 追踪 resolveValueIfNecessary ，发现是在 BeanDefinitionValueResolver 类」 。
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				// 转换之后的属性值
				Object convertedValue = resolvedValue;
				boolean convertible = bw.isWritableProperty(propertyName) && !PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				// 使用用户自定义的类型转换器转换属性值
				if (convertible) {
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				// 存储转换后的属性值，避免每次属性注入时的转换工作
				if (resolvedValue == originalValue) {
					if (convertible) {
						// 设置属性转换之后的值
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				} else if (convertible // 属性是可转换的
						&& originalValue instanceof TypedStringValue //属性原始值是字符串类型
						&& !((TypedStringValue) originalValue).isDynamic() //属性的原始类型值不是动态生成的字符串
						&& !(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) //属性的原始值不是集合或者数组类型
				{
					pv.setConvertedValue(convertedValue);
					// 重新封装属性的值
					deepCopy.add(pv);
				} else {
					resolveNecessary = true;
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		// 标记属性值已经转换过
		if (mpvs != null && !resolveNecessary) {
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		// 进行属性依赖注入，依赖注入的真真正正实现依赖的注入方法在此！！！
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		} catch (BeansException ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}

		/**
		 * 总结 #applyPropertyValues(...) 方法（完成属性转换）：
		 *
		 * 属性值类型不需要转换时，不需要解析属性值，直接准备进行依赖注入。
		 * 属性值需要进行类型转换时，如对其他对象的引用等，首先需要解析属性值，然后对解析后的属性值进行依赖注入。
		 */
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		} else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}

	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 *
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean     the new bean instance we may need to initialize
	 * @param mbd      the bean definition that the bean was created with
	 *                 (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 * <p>
	 * 关于加载bean，在 #doCreateBean(...) 方法，主要用于完成 bean 的创建和初始化工作，我们可以将其分为四个过程：
	 * <p>
	 * 1、#createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) 方法，实例化 bean 。【又长又臭的代码，四种策略实例化bean】
	 * 2、循环依赖的处理。
	 * 3、#populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) 方法，进行属性填充。
	 * 4、#initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) 方法，初始化 Bean 。
	 * <p>
	 * 经历了实例化、填充，终于到了最后一步：初始化Bean，
	 * 初始化 bean 的方法其实就是三个步骤的处理，而这三个步骤主要还是根据用户设定的来进行初始化，这三个过程为：
	 * 1、激活 Aware 方法
	 * 2、后置处理器的应用
	 * 3、激活自定义的 init 方法
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		// 又是安全模式。。
		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				// <1> 激活 Aware 方法，对特殊的 bean 处理：Aware、BeanClassLoaderAware、BeanFactoryAware
				invokeAwareMethods(beanName, bean);
				return null;
			}, getAccessControlContext());
		} else {
			// <1> 激活 Aware 方法，对特殊的 bean 处理：Aware、BeanClassLoaderAware、BeanFactoryAware（具体解析见函数体内）
			invokeAwareMethods(beanName, bean);
		}

		/**
		 * BeanPostProcessor 在前面介绍 bean 加载的过程曾多次遇到，这是 Spring 中开放式框架中必不可少的一个亮点。
		 * BeanPostProcessor 的作用是：如果我们想要在 Spring 容器完成 Bean 的实例化，配置和其他的初始化后添加一些自己的逻辑处理，那么请使用该接口，
		 * 这个接口给与了用户充足的权限去更改或者扩展 Spring，是我们对 Spring 进行扩展和增强处理一个必不可少的接口。
		 *
		 * 其实，逻辑就是通过 #getBeanPostProcessors() 方法，获取定义的 BeanPostProcessor ，然后分别调用其 #postProcessBeforeInitialization(...)、#postProcessAfterInitialization(...) 方法，进行自定义的业务处理。
		 */

		// <2> 后处理器，before，具体解析见函数体内
		Object wrappedBean = bean;
		if (mbd == null || !mbd.isSynthetic()) {
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		// <3> 激活用户自定义的 init 方法
		// 对应 <bean> 标签配置中的 init-method 方法
		try {
			invokeInitMethods(beanName, wrappedBean, mbd);
		} catch (Throwable ex) {
			throw new BeanCreationException((mbd != null ? mbd.getResourceDescription() : null), beanName, "Invocation of init method failed", ex);
		}
		// <2> 后处理器，after
		if (mbd == null || !mbd.isSynthetic()) {
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		return wrappedBean;

		/**
		 * <1> 激活 Aware 方法
		 * Aware ，英文翻译是意识到的，感知的。Spring 提供了诸多 Aware 接口，用于辅助 Spring Bean 以编程的方式调用 Spring 容器，通过实现这些接口，可以增强 Spring Bean 的功能。
		 *
		 * Spring 提供了如下系列的 Aware 接口：
		 * LoadTimeWeaverAware：加载 Spring Bean 时织入第三方模块，如AspectJ
		 * BeanClassLoaderAware：加载 Spring Bean 的类加载器
		 * BootstrapContextAware：资源适配器 BootstrapContext，如JCA,CCI
		 * ResourceLoaderAware：底层访问资源的加载器
		 * BeanFactoryAware：声明 BeanFactory
		 * PortletConfigAware：PortletConfig
		 * PortletContextAware：PortletContext
		 * ServletConfigAware：ServletConfig
		 * ServletContextAware：ServletContext
		 * MessageSourceAware：国际化
		 * ApplicationEventPublisherAware：应用事件
		 * NotificationPublisherAware：JMX通知
		 * BeanNameAware：声明 Spring Bean 的名字
		 */
	}

	/**
	 * 激活 Aware 方法，对特殊的 bean 处理：Aware、BeanClassLoaderAware、BeanFactoryAware
	 *
	 * @param beanName
	 * @param bean
	 */
	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			// BeanNameAware
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			// BeanClassLoaderAware
			if (bean instanceof BeanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			// BeanFactoryAware
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 *
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean     the new bean instance we may need to initialize
	 * @param mbd      the merged bean definition that the bean was created with
	 *                 (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 * <p>
	 * 1、激活实现了InitializingBean接口的重写afterPropertiesSet方法
	 * 2、激活用户自定义的 init 方法，对应 <bean> 标签配置中的 init-method 方法（这里其实就是在这个bean实例化一个对象的时候，执行这个方法里面的内容）
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd) throws Throwable {
		// 首先会检查是否是 InitializingBean ，如果是的话需要调用 afterPropertiesSet()
		boolean isInitializingBean = (bean instanceof InitializingBean);
		if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			// 安全模式。。
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						// <1> 属性初始化的处理
						((InitializingBean) bean).afterPropertiesSet();
						return null;
					}, getAccessControlContext());
				} catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			} else {
				// <1> 属性初始化的处理
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		if (mbd != null && bean.getClass() != NullBean.class) {
			String initMethodName = mbd.getInitMethodName();
			if (StringUtils.hasLength(initMethodName) && !(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) && !mbd.isExternallyManagedInitMethod(initMethodName)) {
				// <2> 激活用户自定义的初始化方法
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}

		/**
		 * 首先，检查是否为 InitializingBean 。如果是的话，需要执行 #afterPropertiesSet() 方法，
		 * 因为我们除了可以使用 init-method 来自定初始化方法外，还可以实现 InitializingBean 接口。接口仅有一个 #afterPropertiesSet() 方法。
		 * 两者的执行先后顺序是先 <1> 的 #afterPropertiesSet() 方法，后 <2> 的 init-method 对应的方法。
		 */
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 *
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd)
			throws Throwable {

		String initMethodName = mbd.getInitMethodName();
		Assert.state(initMethodName != null, "No init method set");
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			} else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod);

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				ReflectionUtils.makeAccessible(methodToInvoke);
				return null;
			});
			try {
				AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
						() -> methodToInvoke.invoke(bean), getAccessControlContext());
			} catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		} else {
			try {
				ReflectionUtils.makeAccessible(methodToInvoke);
				methodToInvoke.invoke(bean);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}

	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 *
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 *
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}

	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}

	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				} else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
