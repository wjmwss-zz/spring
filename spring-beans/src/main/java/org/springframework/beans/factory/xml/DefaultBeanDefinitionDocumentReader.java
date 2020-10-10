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

package org.springframework.beans.factory.xml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";

	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;

	/**
	 * 注册 BeanDefinition
	 *
	 * @param doc           the DOM document
	 * @param readerContext the current context of the reader
	 *                      (includes the target registry and the resource being parsed)
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;
		// 获得 XML Document Root Element
		// 执行注册 BeanDefinition
		doRegisterBeanDefinitions(doc.getDocumentElement());
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}

	/**
	 * 注册 BeanDefinition
	 * <br>
	 * Register each bean definition within the given root {@code <beans/>} element.
	 *
	 * @param root
	 */
	@SuppressWarnings("deprecation")  // for Environment.acceptsProfiles(String...)
	protected void doRegisterBeanDefinitions(Element root) {
		// Any nested <beans> elements will cause recursion in this method. In
		// order to propagate and preserve <beans> default-* attributes correctly,
		// keep track of the current (parent) delegate, which may be null. Create
		// the new (child) delegate with a reference to the parent for fallback purposes,
		// then ultimately reset this.delegate back to its original (parent) reference.
		// this behavior emulates a stack of delegates without actually necessitating one.

		// 记录老的 BeanDefinitionParserDelegate 对象
		BeanDefinitionParserDelegate parent = this.delegate;
		// <1> 创建 BeanDefinitionParserDelegate 对象，并进行设置到 delegate
		this.delegate = createDelegate(getReaderContext(), root, parent);
		// <2> 检查 <beans /> 根标签的命名空间是否为空，或者是 http://www.springframework.org/schema/beans
		if (this.delegate.isDefaultNamespace(root)) {
			// <2.1> 处理 profile 属性。可参见《Spring3自定义环境配置 <beans profile="">》http://nassir.iteye.com/blog/1535799
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				// <2.2> 使用分隔符切分，可能有多个 profile 。
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				// We cannot use Profiles.of(...) since profile expressions are not supported
				// in XML config. See SPR-12458 for details.
				// <2.3> 如果所有 profile 都无效，则 return 不进行注册
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec + "] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}

		// <3> 解析前处理
		preProcessXml(root);
		// <4> 解析
		parseBeanDefinitions(root, this.delegate);
		// <5> 解析后处理
		postProcessXml(root);

		// 设置 delegate 回老的 BeanDefinitionParserDelegate 对象
		this.delegate = parent;

		/**
		 * <1> 处，创建 BeanDefinitionParserDelegate 对象，并进行设置到 delegate 。<br>
		 * BeanDefinitionParserDelegate 是一个重要的类，它负责解析 BeanDefinition，定义了解析 XML Element 的各种方法，初始化代码如下：<br>
		 * <br>
		 * protected BeanDefinitionParserDelegate createDelegate(XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {
		 *     // 创建 BeanDefinitionParserDelegate 对象
		 *     BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		 *     // 初始化默认
		 *     delegate.initDefaults(root, parentDelegate);
		 *     return delegate;
		 * }
		 * <br>
		 * <2> 处，检查 <beans /> 根标签的命名空间是否为空，或者是 http://www.springframework.org/schema/beans 。<br>
		 * <2.1> 处，判断是否 <beans /> 上配置了 profile 属性。<br>
		 * <2.2> 处，使用分隔符切分，可能有多个 profile 。<br>
		 * <2.3> 处，判断，如果所有 profile 都无效，则 return 不进行注册。<br>
		 * <4> 处，调用 #parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) 方法，进行解析逻辑。<br>
		 * <3> / <5> 处，解析前后的处理，目前这两个方法都是空实现，交由子类来实现。代码如下：<br>
		 * <br>
		 * protected void preProcessXml(Element root) {}
		 * protected void postProcessXml(Element root) {}
		 *
		 */
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**
	 * Parse the elements at the root level in the document:
	 * "import", "alias", "bean".
	 *
	 * @param root the DOM root element of the document
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		// <1> 如果根节点使用默认命名空间，执行默认解析（默认命名空间：http://www.springframework.org/schema/beans）
		if (delegate.isDefaultNamespace(root)) {
			// 遍历子节点
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				if (node instanceof Element) {
					Element ele = (Element) node;
					// <1> 如果该节点使用默认命名空间，执行默认解析
					if (delegate.isDefaultNamespace(ele)) {
						parseDefaultElement(ele, delegate);
					} else {
						// 如果该节点非默认命名空间，执行自定义解析
						delegate.parseCustomElement(ele);
					}
				}
			}
		} else {
			// <2> 如果根节点非默认命名空间，执行自定义解析
			delegate.parseCustomElement(root);
		}

		/**
		 * Spring 有两种 Bean 声明方式：<br>
		 * 配置文件式声明：<bean id="studentService" class="org.springframework.core.StudentService" /> 。对应 <1> 处。<br>
		 * 自定义注解方式：<tx:annotation-driven> 。对应 <2> 处。<br>
		 * <1> 处，如果根节点或子节点使用默认命名空间，调用 #parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) 方法，执行默认解析。代码如下：<br>
		 *<br>
		 * private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		 * 	if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) { // import
		 * 		importBeanDefinitionResource(ele);
		 *        } else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) { // alias
		 * 		processAliasRegistration(ele);
		 *    } else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) { // bean
		 * 		processBeanDefinition(ele, delegate);
		 *    } else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) { // beans
		 * 		// recurse
		 * 		doRegisterBeanDefinitions(ele);
		 *    }
		 * }
		 * <br>
		 * <2> 处，如果根节点或子节点不使用默认命名空间，调用 BeanDefinitionParserDelegate#parseCustomElement(Element ele) 方法，执行自定义解析。
		 */

		/**
		 * 该函数完毕，则 BeanDefinition 的解析过程已经全部完成，下面做一个简要的总结：
		 * 1、解析 BeanDefinition 的入口在 DefaultBeanDefinitionDocumentReader 的#parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) 方法。
		 * 该方法会根据命令空间来判断标签是默认标签还是自定义标签，其中：
		 * 	①、默认标签，由 #parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) 方法来实现
		 *  ②、自定义标签，由 BeanDefinitionParserDelegate 的 #parseCustomElement(Element ele, @Nullable BeanDefinition containingBd) 方法来实现。
		 * 在默认标签解析中，会根据标签名称的不同进行 import、alias、bean、beans 四大标签进行处理。其中 bean 标签的解析为核心，它由 processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) 方法实现。
		 *
		 * 2、processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) 方法，开始进入解析核心工作，分为三步：
		 * 	①、解析默认标签下的默认标签：BeanDefinitionParserDelegate#parseBeanDefinitionElement(Element ele, ...) 方法。该方法会依次解析 <bean> 标签的属性、各个子元素，解析完成后返回一个 GenericBeanDefinition 实例对象。
		 * 	②、解析默认标签下的自定义标签：BeanDefinitionParserDelegate#decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder definitionHolder) 方法。
		 * 	③、注册解析的 BeanDefinition：BeanDefinitionReaderUtils#registerBeanDefinition(BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry) 方法。
		 *
		 * 注：
		 * parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate)
		 * 和
		 * processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate)
		 * 不一样，注意看
		 */
	}

	/**
	 * 默认标签解析
	 *
	 * @param ele
	 * @param delegate
	 */
	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			// import
			importBeanDefinitionResource(ele);
		} else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			// alias
			processAliasRegistration(ele);
		} else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			// bean（核心）
			processBeanDefinition(ele, delegate);
		} else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// beans
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		// <1> 获取 resource 的属性值
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		// 为空，直接退出
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}

		// Resolve system properties: e.g. "${user.dir}"
		// <2> 解析系统属性，格式如 ："${user.dir}"
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		// 实际 Resource 集合，即 import 的地址里，有哪些 Resource 资源
		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// Discover whether the location is an absolute or relative URI
		// <3> 判断 location 是相对路径还是绝对路径
		boolean absoluteLocation = false;
		try {
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		} catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}

		// Absolute or relative?
		// <4> 绝对路径
		if (absoluteLocation) {
			try {
				// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition 们
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			} catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		} else {
			// No URL -> considering resource location as relative to the current file.
			// <5> 相对路径
			try {
				int importCount;
				// 创建相对地址的 Resource
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				// 存在
				if (relativeResource.exists()) {
					// 加载 relativeResource 中的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					// 添加到 actualResources 中
					actualResources.add(relativeResource);
					// 不存在
				} else {
					// 获得根路径地址
					String baseLocation = getReaderContext().getResource().getURL().toString();
					// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			} catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			} catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from relative location [" + location + "]", ele, ex);
			}
		}
		// <6> 解析成功后，进行监听器激活处理
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));

		/**
		 * spring.xml 配置文件中，使用 import 标签的方式导入其他模块的配置文件，如：
		 *  <\import resource="spring-student.xml"/>
		 *  <\import resource="spring-student-dtd.xml"/>
		 * <br>
		 * 如果有配置需要修改直接修改相应配置文件即可。
		 * 若有新的模块需要引入直接增加 import 即可。
		 * 这样大大简化了配置后期维护的复杂度，同时也易于管理。
		 * <br>
		 * 解析 import 标签的过程较为清晰，整个过程如下：
		 * <1> 处，获取 source 属性的值，该值表示资源的路径。
		 * <2> 处，解析路径中的系统属性，如 "${user.dir}" 。
		 * <3> 处，判断资源路径 location 是绝对路径还是相对路径 。
		 * <4> 处，如果是绝对路径，则调递归调用 Bean 的解析过程，进行另一次的解析。
		 * <5> 处，如果是相对路径，则先计算出绝对路径得到 Resource，然后进行解析。
		 * <6> 处，通知监听器，完成解析。
		 * <br>
		 * 处理绝对路径：
		 * 首先，获取 ResourceLoader 对象。
		 * 然后，根据不同的 ResourceLoader 执行不同的逻辑，主要是可能存在多个 Resource 。
		 * 最终，都会回归到 XmlBeanDefinitionReader#loadBeanDefinitions(Resource... resources) 方法，所以这是一个递归的过程。
		 * 另外，获得到的 Resource 的对象或数组，都会添加到 actualResources 中。
		 * <br>
		 * 处理相对路径：
		 * 如果 location 是相对路径，则会根据相应的 Resource 计算出相应的相对路径的 Resource 对象 ，然后：
		 * 若该 Resource 存在，则调用 XmlBeanDefinitionReader#loadBeanDefinitions(Resource... resources) 方法，进行 BeanDefinition 加载。
		 * 否则，构造一个绝对 location( 即 StringUtils.applyRelativePath(baseLocation, location) 处的代码)，并调用 #loadBeanDefinitions(String location, Set<Resource> actualResources) 方法，与绝对路径过程一样。
		 * <br>
		 * 无论是处理绝对路径还是相对路径，最终都是调用 XmlBeanDefinitionReader#loadBeanDefinitions(Resource... resources) 方法，进行 BeanDefinition 加载。
		 * <br>
		 * import 标签解析完毕，整个过程比较清晰明了：
		 * 获取 source 属性值，得到正确的资源路径，然后调用 XmlBeanDefinitionReader#loadBeanDefinitions(Resource... resources) 方法，进行递归的 BeanDefinition 加载。
		 */
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 */
	protected void processAliasRegistration(Element ele) {
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				getReaderContext().getRegistry().registerAlias(name, alias);
			} catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
	 * <p>
	 * DefaultBeanDefinitionDocumentReader 的 #processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) 方法，负责 <bean> 标签的解析：
	 * 1、在解析过程中，首先调用 BeanDefinitionParserDelegate#parseBeanDefinitionElement(Element ele) 方法，完成基本属性、默认标签的解析。
	 * 基本属性、
	 * 默认标签：<meta>、<lookup-method>、<replace-method>、<constructor-arg>、<property>、<qualifier>
	 * 2、如果解析成功（返回的 bdHolder != null ），完成自定义标签元素的解析，调用 BeanDefinitionParserDelegate#decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder definitionHolder) 方法；
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// 进行 bean 元素解析。
		// <1> 如果解析成功，则返回 BeanDefinitionHolder 对象。BeanDefinitionHolder 为 name 和 alias 的 BeanDefinition 对象
		// 如果解析失败，则返回 null 。
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		if (bdHolder != null) {
			// <2> 进行自定义标签处理
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				// Register the final decorated instance.
				// <3> 进行 BeanDefinition 的注册
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			} catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" + bdHolder.getBeanName() + "'", ele, ex);
			}
			// Send registration event.
			// <4> 发出响应事件，通知相关的监听器，已完成该 Bean 标签的解析。
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}

		/**
		 * 解析工作分为三步：
		 * 1、解析默认标签。
		 * 2、解析默认标签后下得自定义标签。
		 * 3、注册解析后的 BeanDefinition 。
		 * 经过前面两个步骤的解析，这时的 BeanDefinition 已经可以满足后续的使用要求了，那么接下来的工作就是将这些 BeanDefinition 进行注册，也就是完成第三步。
		 */

		/**
		 * 整个过程分为四个步骤：
		 *
		 * 调用 BeanDefinitionParserDelegate#parseBeanDefinitionElement(Element ele, BeanDefinitionParserDelegate delegate) 方法，进行元素解析。
		 * 如果解析失败，则返回 null，错误由 ProblemReporter 处理。
		 * 如果解析成功，则返回 BeanDefinitionHolder 实例 bdHolder 。
		 * 若实例 bdHolder 不为空，则调用 BeanDefinitionParserDelegate#decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder bdHolder) 方法，进行自定义标签处理。
		 * 解析完成后，则调用 BeanDefinitionReaderUtils#registerBeanDefinition(BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry) 方法，对 bdHolder 进行 BeanDefinition 的注册。
		 * 发出响应事件，通知相关的监听器，完成 Bean 标签解析。
		 */
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 *
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 *
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
