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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.lang.Nullable;

import java.security.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 *
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/**
	 * Determine the type for the given FactoryBean.
	 *
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	@Nullable
	protected Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Class<?>>) factoryBean::getObjectType, getAccessControlContext());
			} else {
				return factoryBean.getObjectType();
			}
		} catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 *
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	@Nullable
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * Cache of singleton objects created by FactoryBeans: FactoryBean name to object.
	 * 缓存 FactoryBean 创建的单例 Bean 对象的映射
	 * beanName ===> Bean 对象
	 */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 *
	 * @param factory           the FactoryBean instance
	 * @param beanName          the name of the bean
	 * @param shouldPostProcess whether the bean is subject to post-processing
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 * <p>
	 * 从 FactoryBean ( beanInstance ) 中，获取 Bean 实例对象
	 * 使用 FactoryBean 获得 Bean 对象的核心处理方法
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		// <1> 为单例模式且缓存中存在
		if (factory.isSingleton() && containsSingleton(beanName)) {
			synchronized (getSingletonMutex()) {// <1.1> 单例锁  mutex互斥，具体解析见函数体内
				// <1.2> 从 factoryBeanObjectCache 缓存中获取实例对象 object
				Object object = this.factoryBeanObjectCache.get(beanName);
				if (object == null) {
					// 为空，则从 FactoryBean 中获取对象，具体解析见函数体内
					object = doGetObjectFromFactoryBean(factory, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (e.g. because of circular reference processing triggered by custom getBean calls)
					// 从缓存中获取
					Object alreadyThere = this.factoryBeanObjectCache.get(beanName);
					if (alreadyThere != null) {
						object = alreadyThere;
					} else {
						/**
						 * <1.3> 如果需要后续处理( shouldPostProcess = true )，则进行进一步处理，步骤如下：
						 *
						 * 若该 Bean 处于创建中（#isSingletonCurrentlyInCreation(String beanName) 方法返回 true ），则返回非处理的 Bean 对象，而不是存储它。
						 * 调用 #beforeSingletonCreation(String beanName) 方法，进行创建之前的处理。默认实现：将该 Bean 标志为当前创建中。
						 * 调用 #postProcessObjectFromFactoryBean(Object object, String beanName) 方法，对从 FactoryBean 获取的 Bean 实例对象进行后置处理。
						 * 调用 #afterSingletonCreation(String beanName) 方法，进行创建 Bean 之后的处理，默认实现：是将该 bean 标记为不再在创建中。
						 * <1.4>中： 最后，加入到 factoryBeanObjectCache 缓存中。
						 * 该方法应该就是创建 Bean 实例对象中的核心方法之一了。这里我们关注三个方法：
						 *
						 * #beforeSingletonCreation(String beanName)
						 * #afterSingletonCreation(String beanName)
						 * #postProcessObjectFromFactoryBean(Object object, String beanName)
						 *
						 * 前两个方法是非常重要的操作，因为他们记录着 Bean 的加载状态，是检测当前 Bean 是否处于创建中的关键之处，对解决 Bean 循环依赖起着关键作用。
						 * #beforeSingletonCreation(String beanName) 方法，用于添加标志，当前 bean 正处于创建中
						 * #afterSingletonCreation(String beanName) 方法，用于移除标记，当前 Bean 不处于创建中。
						 *
						 * 而#isSingletonCurrentlyInCreation(String beanName) 方法，就是根据 singletonsCurrentlyInCreation 集合中是否包含了 beanName，
						 * 来检测当前 Bean 是否处于创建之中的。
						 */
						if (shouldPostProcess) {
							// 若该 Bean 处于创建中，则返回非处理对象，而不是存储它，具体解析见函数体内
							if (isSingletonCurrentlyInCreation(beanName)) {
								// Temporarily return non-post-processed object, not storing it yet..
								return object;
							}
							// 单例 Bean 的前置处理，具体解析见函数体内
							beforeSingletonCreation(beanName);
							try {
								// 对从 FactoryBean 获取的对象进行后置处理
								// 生成的对象将暴露给 bean 引用，具体解析见函数体内（其实就是直接返回传进去的object，该方法目前默认实现就是直接返回对象）
								/**
								 * 当然，子类可以重写，例如应用后置处理器。org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory 抽象类，对其提供了实现，代码如下：
								 * // AbstractAutowireCapableBeanFactory.java
								 * protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
								 * 	return applyBeanPostProcessorsAfterInitialization(object, beanName);
								 * }
								 * 该方法的定义为：对所有的 {@code postProcessAfterInitialization} 进行回调注册 BeanPostProcessors ，让他们能够后期处理从 FactoryBean 中获取的对象。
								 * 具体实现：AbstractAutowireCapableBeanFactory#applyBeanPostProcessorsAfterInitialization(Object,String)，具体解析见函数体内
								 *
								 * 对于后置处理器，这里我们不做过多阐述，后面会专门的博文进行详细介绍，
								 * 这里我们只需要记住一点：尽可能保证所有 bean 初始化后都会调用注册的 BeanPostProcessor#postProcessAfterInitialization(Object bean, String beanName) 方法进行处理，在实际开发过程中大可以针对此特性设计自己的业务逻辑。
								 */
								object = postProcessObjectFromFactoryBean(object, beanName);
							} catch (Throwable ex) {
								throw new BeanCreationException(beanName, "Post-processing of FactoryBean's singleton object failed", ex);
							} finally {
								// 单例 Bean 的后置处理，具体解析见函数体内
								afterSingletonCreation(beanName);
							}
						}
						// <1.4> 添加到 factoryBeanObjectCache 中，进行缓存
						if (containsSingleton(beanName)) {
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				return object;
			}
			// <2> .
		} else {
			// 为空，则从 FactoryBean 中获取对象
			Object object = doGetObjectFromFactoryBean(factory, beanName);
			// 需要后续处理
			if (shouldPostProcess) {
				try {
					// 对从 FactoryBean 获取的对象进行后处理
					// 生成的对象将暴露给 bean 引用
					object = postProcessObjectFromFactoryBean(object, beanName);
				} catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			return object;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 *
	 * @param factory  the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 * <p>
	 * 从 FactoryBean 获取对象，其实内部就是调用 FactoryBean#getObject() 方法
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) throws BeanCreationException {
		Object object;
		try {
			// 需要权限验证
			if (System.getSecurityManager() != null) {
				AccessControlContext acc = getAccessControlContext();
				try {
					// 从 FactoryBean 中，获得 Bean 对象
					object = AccessController.doPrivileged((PrivilegedExceptionAction<Object>) factory::getObject, acc);
				} catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			} else {
				// 从 FactoryBean 中，获得 Bean 对象
				object = factory.getObject();
			}
		} catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		} catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully initialized yet: Many FactoryBeans just return null then.
		// 如果 FactoryBean 无法获取到 Bean，若要获取的 bean 正在创建中，则排除异常；否则返回一个 NullBean
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 *
	 * @param object   the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 *                                                  <p>
	 *                                                  对从 FactoryBean 处获取的 Bean 实例对象进行后置处理。其默认实现是直接返回 object 对象，不做任何处理
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 *
	 * @param beanName     the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return (FactoryBean<?>) beanInstance;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanObjectCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanObjectCache.clear();
		}
	}

	/**
	 * Return the security context for this bean factory. If a security manager
	 * is set, interaction with the user code will be executed using the privileged
	 * of the security context returned by this method.
	 *
	 * @see AccessController#getContext()
	 */
	protected AccessControlContext getAccessControlContext() {
		return AccessController.getContext();
	}

}
