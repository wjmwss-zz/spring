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

package org.springframework.beans.factory.xml;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.parsing.*;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Constants;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.xml.SimpleSaxErrorHandler;
import org.springframework.util.xml.XmlValidationModeDetector;
import org.w3c.dom.Document;
import org.xml.sax.*;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Bean definition reader for XML bean definitions.
 * Delegates the actual XML document reading to an implementation
 * of the {@link BeanDefinitionDocumentReader} interface.
 *
 * <p>Typically applied to a
 * {@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
 * or a {@link org.springframework.context.support.GenericApplicationContext}.
 *
 * <p>This class loads a DOM document and applies the BeanDefinitionDocumentReader to it.
 * The document reader will register each bean definition with the given bean factory,
 * talking to the latter's implementation of the
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry} interface.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @see #setDocumentReaderClass
 * @see BeanDefinitionDocumentReader
 * @see DefaultBeanDefinitionDocumentReader
 * @see BeanDefinitionRegistry
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 * @see org.springframework.context.support.GenericApplicationContext
 * @since 26.11.2003
 */
public class XmlBeanDefinitionReader extends AbstractBeanDefinitionReader {

	/**
	 * Constants instance for this class.
	 */
	private static final Constants constants = new Constants(XmlBeanDefinitionReader.class);

	private boolean namespaceAware = false;

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	private ReaderEventListener eventListener = new EmptyReaderEventListener();

	private SourceExtractor sourceExtractor = new NullSourceExtractor();

	@Nullable
	private NamespaceHandlerResolver namespaceHandlerResolver;

	private DocumentLoader documentLoader = new DefaultDocumentLoader();

	@Nullable
	private EntityResolver entityResolver;

	private ErrorHandler errorHandler = new SimpleSaxErrorHandler(logger);

	/**
	 * Create new XmlBeanDefinitionReader for the given bean factory.
	 *
	 * @param registry the BeanFactory to load bean definitions into,
	 *                 in the form of a BeanDefinitionRegistry
	 *                 <p>
	 *                 初始化ResourceLoader
	 */
	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
		super(registry);
	}

	/**
	 * Set whether to use XML validation. Default is {@code true}.
	 * <p>This method switches namespace awareness on if validation is turned off,
	 * in order to still process schema namespaces properly in such a scenario.
	 *
	 * @see #setValidationMode
	 * @see #setNamespaceAware
	 */
	public void setValidating(boolean validating) {
		this.validationMode = (validating ? VALIDATION_AUTO : VALIDATION_NONE);
		this.namespaceAware = !validating;
	}

	/**
	 * Set the validation mode to use by name. Defaults to {@link #VALIDATION_AUTO}.
	 *
	 * @see #setValidationMode
	 */
	public void setValidationModeName(String validationModeName) {
		setValidationMode(constants.asNumber(validationModeName).intValue());
	}

	/**
	 * Set the validation mode to use. Defaults to {@link #VALIDATION_AUTO}.
	 * <p>Note that this only activates or deactivates validation itself.
	 * If you are switching validation off for schema files, you might need to
	 * activate schema namespace support explicitly: see {@link #setNamespaceAware}.
	 */
	public void setValidationMode(int validationMode) {
		this.validationMode = validationMode;
	}

	/**
	 * Return the validation mode to use.
	 */
	public int getValidationMode() {
		return this.validationMode;
	}

	/**
	 * Set whether or not the XML parser should be XML namespace aware.
	 * Default is "false".
	 * <p>This is typically not needed when schema validation is active.
	 * However, without validation, this has to be switched to "true"
	 * in order to properly process schema namespaces.
	 */
	public void setNamespaceAware(boolean namespaceAware) {
		this.namespaceAware = namespaceAware;
	}

	/**
	 * Return whether or not the XML parser should be XML namespace aware.
	 */
	public boolean isNamespaceAware() {
		return this.namespaceAware;
	}

	/**
	 * Specify which {@link org.springframework.beans.factory.parsing.ProblemReporter} to use.
	 * <p>The default implementation is {@link org.springframework.beans.factory.parsing.FailFastProblemReporter}
	 * which exhibits fail fast behaviour. External tools can provide an alternative implementation
	 * that collates errors and warnings for display in the tool UI.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Specify which {@link ReaderEventListener} to use.
	 * <p>The default implementation is EmptyReaderEventListener which discards every event notification.
	 * External tools can provide an alternative implementation to monitor the components being
	 * registered in the BeanFactory.
	 */
	public void setEventListener(@Nullable ReaderEventListener eventListener) {
		this.eventListener = (eventListener != null ? eventListener : new EmptyReaderEventListener());
	}

	/**
	 * Specify the {@link SourceExtractor} to use.
	 * <p>The default implementation is {@link NullSourceExtractor} which simply returns {@code null}
	 * as the source object. This means that - during normal runtime execution -
	 * no additional source metadata is attached to the bean configuration metadata.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new NullSourceExtractor());
	}

	/**
	 * Specify the {@link NamespaceHandlerResolver} to use.
	 * <p>If none is specified, a default instance will be created through
	 * {@link #createDefaultNamespaceHandlerResolver()}.
	 */
	public void setNamespaceHandlerResolver(@Nullable NamespaceHandlerResolver namespaceHandlerResolver) {
		this.namespaceHandlerResolver = namespaceHandlerResolver;
	}

	/**
	 * Specify the {@link DocumentLoader} to use.
	 * <p>The default implementation is {@link DefaultDocumentLoader}
	 * which loads {@link Document} instances using JAXP.
	 */
	public void setDocumentLoader(@Nullable DocumentLoader documentLoader) {
		this.documentLoader = (documentLoader != null ? documentLoader : new DefaultDocumentLoader());
	}

	/**
	 * Set a SAX entity resolver to be used for parsing.
	 * <p>By default, {@link ResourceEntityResolver} will be used. Can be overridden
	 * for custom entity resolution, for example relative to some specific base path.
	 */
	public void setEntityResolver(@Nullable EntityResolver entityResolver) {
		this.entityResolver = entityResolver;
	}

	/**
	 * Return the EntityResolver to use, building a default resolver
	 * if none specified.
	 * <p>
	 * #getEntityResolver() 方法，返回指定的解析器，如果没有指定，则构造一个未指定的默认解析器。
	 */
	protected EntityResolver getEntityResolver() {
		if (this.entityResolver == null) {
			// Determine default EntityResolver to use.
			/**
			 * 如果 ResourceLoader 不为 null，则根据指定的 ResourceLoader 创建一个 ResourceEntityResolver 对象。
			 * 如果 ResourceLoader 为 null ，则创建 一个 DelegatingEntityResolver 对象。该 Resolver 委托给默认的 BeansDtdResolver 和 PluggableSchemaResolver 。
			 */
			ResourceLoader resourceLoader = getResourceLoader();
			if (resourceLoader != null) {
				this.entityResolver = new ResourceEntityResolver(resourceLoader);
			} else {
				this.entityResolver = new DelegatingEntityResolver(getBeanClassLoader());
			}
			/**
			 * 上面的方法，一共涉及四个 EntityResolver 的子类：
			 * 1、org.springframework.beans.factory.xm.BeansDtdResolver ：实现 EntityResolver 接口，Spring Bean dtd 解码器，用来从 classpath 或者 jar 文件中加载 dtd 。部分代码如下：
			 * private static final String DTD_EXTENSION = ".dtd";
			 * private static final String DTD_NAME = "spring-beans";
			 *
			 * 2、org.springframework.beans.factory.xml.PluggableSchemaResolver ，实现 EntityResolver 接口，读取 classpath 下的所有 "META-INF/spring.schemas" 成一个 namespaceURI 与 Schema 文件地址的 map 。代码如下：
			 * // 默认 {@link #schemaMappingsLocation} 地址
			 * public static final String DEFAULT_SCHEMA_MAPPINGS_LOCATION = "META-INF/spring.schemas";
			 *
			 * @Nullable
			 * private final ClassLoader classLoader;
			 *
			 * // Schema 文件地址
			 * private final String schemaMappingsLocation;
			 * // Stores the mapping of schema URL -> local schema path.
			 *
			 * @Nullable
			 * private volatile Map<String, String> schemaMappings; // namespaceURI 与 Schema 文件地址的映射集合
			 *
			 * 3、org.springframework.beans.factory.xml.DelegatingEntityResolver ：实现 EntityResolver 接口，分别代理 dtd 的 BeansDtdResolver 和 xml schemas 的 PluggableSchemaResolver 。代码如下：
			 * // Suffix for DTD files.
			 * public static final String DTD_SUFFIX = ".dtd";
			 *
			 * // Suffix for schema definition files.
			 * public static final String XSD_SUFFIX = ".xsd";
			 *
			 * private final EntityResolver dtdResolver;
			 *
			 * private final EntityResolver schemaResolver;
			 *
			 * // 默认
			 * public DelegatingEntityResolver(@Nullable ClassLoader classLoader) {
			 *	  this.dtdResolver = new BeansDtdResolver();
			 *	  this.schemaResolver = new PluggableSchemaResolver(classLoader);
			 * }
			 *
			 * // 自定义
			 * public DelegatingEntityResolver(EntityResolver dtdResolver, EntityResolver schemaResolver) {
			 *	  Assert.notNull(dtdResolver, "'dtdResolver' is required");
			 *	  Assert.notNull(schemaResolver, "'schemaResolver' is required");
			 *	  this.dtdResolver = dtdResolver;
			 *	  this.schemaResolver = schemaResolver;
			 * }
			 *
			 * 4、org.springframework.beans.factory.xml.ResourceEntityResolver ：继承自 DelegatingEntityResolver 类，通过 ResourceLoader 来解析实体的引用。代码如下：
			 * private final ResourceLoader resourceLoader;
			 *
			 * public ResourceEntityResolver(ResourceLoader resourceLoader) {
			 * 	  super(resourceLoader.getClassLoader());
			 * 	  this.resourceLoader = resourceLoader;
			 * }
			 *
			 *
			 * EntityResolver 的作用：
			 * EntityResolver 的作用就是，通过实现它，应用可以自定义如何寻找【验证文件】的逻辑。
			 *
			 * 在 loadDocument 方法中涉及一个参数 EntityResolver ，何为EntityResolver？
			 * 官网这样解释：如果 SAX 应用程序需要实现自定义处理外部实体，则必须实现此接口并使用 setEntityResolver 方法向SAX 驱动器注册一个实例。
			 * 也就是说，对于解析一个XML，SAX 首先读取该 XML 文档上的声明，根据声明去寻找相应的 DTD 定义，以便对文档进行一个验证。
			 * 默认的寻找规则，即通过网络（实现上就是声明的DTD的URI地址）来下载相应的DTD声明，并进行认证。
			 * 下载的过程是一个漫长的过程，而且当网络中断或不可用时，这里会报错，就是因为相应的DTD声明没有被找到的原因。
			 *
			 * EntityResolver 的作用是项目本身就可以提供一个如何寻找 DTD 声明的方法，即由程序来实现寻找 DTD 声明的过程，
			 * 比如我们将 DTD 文件放到项目中某处，在实现时直接将此文档读取并返回给 SAX 即可。这样就避免了通过网络来寻找相应的声明。
			 *
			 * 关注 DelegatingEntityResolver#resolveEntity(String publicId, String systemId)
			 * 关注 BeansDtdResolver#resolveEntity(String publicId, String systemId)
			 *
			 *
			 *
			 */
		}
		return this.entityResolver;
	}

	/**
	 * Set an implementation of the {@code org.xml.sax.ErrorHandler}
	 * interface for custom handling of XML parsing errors and warnings.
	 * <p>If not set, a default SimpleSaxErrorHandler is used that simply
	 * logs warnings using the logger instance of the view class,
	 * and rethrows errors to discontinue the XML transformation.
	 *
	 * @see SimpleSaxErrorHandler
	 */
	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * Specify the {@link BeanDefinitionDocumentReader} implementation to use,
	 * responsible for the actual reading of the XML bean definition document.
	 * <p>The default is {@link DefaultBeanDefinitionDocumentReader}.
	 *
	 * @param documentReaderClass the desired BeanDefinitionDocumentReader implementation class
	 */
	public void setDocumentReaderClass(Class<? extends BeanDefinitionDocumentReader> documentReaderClass) {
		this.documentReaderClass = documentReaderClass;
	}

	/**
	 * 当前线程，正在加载的 EncodedResource 集合
	 */
	private final ThreadLocal<Set<EncodedResource>> resourcesCurrentlyBeingLoaded =
			new NamedThreadLocal<Set<EncodedResource>>("XML bean definition resources currently being loaded") {
				@Override
				protected Set<EncodedResource> initialValue() {
					return new HashSet<>(4);
				}
			};

	/**
	 * IoC 之加载 BeanDefinition：XML Resource => XML Document<br>
	 * <br>
	 * 先对 Resource 资源封装成 org.springframework.core.io.support.EncodedResource 对象<br>
	 * 这里为什么需要将 Resource 封装成 EncodedResource 呢？<br>
	 * 主要是为了对 Resource 进行编码，保证内容读取的正确性
	 *
	 * @param resource the resource descriptor
	 * @return
	 * @throws BeanDefinitionStoreException
	 */
	@Override
	public int loadBeanDefinitions(Resource resource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(new EncodedResource(resource));
	}

	/**
	 * 加载资源过程
	 *
	 * @param encodedResource
	 * @return
	 * @throws BeanDefinitionStoreException
	 */
	public int loadBeanDefinitions(EncodedResource encodedResource) throws BeanDefinitionStoreException {
		Assert.notNull(encodedResource, "EncodedResource must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("Loading XML bean definitions from " + encodedResource);
		}

		// <1> 获取已经加载过的资源
		Set<EncodedResource> currentResources = this.resourcesCurrentlyBeingLoaded.get();

		// 将当前资源加入记录中。如果已存在，抛出异常
		if (!currentResources.add(encodedResource)) {
			throw new BeanDefinitionStoreException(
					"Detected cyclic loading of " + encodedResource + " - check your import definitions!");
		}

		// <2> 从 EncodedResource 获取封装的 Resource ，并从 Resource 中获取其中的 InputStream
		try (InputStream inputStream = encodedResource.getResource().getInputStream()) {
			InputSource inputSource = new InputSource(inputStream);
			if (encodedResource.getEncoding() != null) {
				inputSource.setEncoding(encodedResource.getEncoding());
			}
			// 核心逻辑部分，执行加载 BeanDefinition
			return doLoadBeanDefinitions(inputSource, encodedResource.getResource());
		} catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"IOException parsing XML document from " + encodedResource.getResource(), ex);
		} finally {
			// 从缓存中剔除该资源 <3>
			currentResources.remove(encodedResource);
			if (currentResources.isEmpty()) {
				this.resourcesCurrentlyBeingLoaded.remove();
			}
		}
		/**
		 * <1> 处，通过 resourcesCurrentlyBeingLoaded.get() 代码，来获取已经加载过的资源，然后将 encodedResource 加入其中，<br>
		 * 如果 resourcesCurrentlyBeingLoaded 中已经存在该资源，则抛出 BeanDefinitionStoreException 异常。<br>
		 * 为什么需要这么做呢？<br>
		 * 答案在 "Detected cyclic loading" ，避免一个 EncodedResource 在加载时，还没加载完成，又加载自身，从而导致死循环。<br>
		 * 也因此，在 <3> 处，当一个 EncodedResource 加载完成后，需要从缓存中剔除。<br>
		 * <br>
		 * <2> 处理，从 encodedResource 获取封装的 Resource 资源，并从 Resource 中获取相应的 InputStream ，
		 * 然后将 InputStream 封装为 InputSource ，最后调用 #doLoadBeanDefinitions(InputSource inputSource, Resource resource) 方法，<br>
		 * 执行加载 Bean Definition 的真正逻辑。
		 */
	}

	/**
	 * Load bean definitions from the specified XML file.
	 *
	 * @param inputSource the SAX InputSource to read from
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(inputSource, "resource loaded through SAX InputSource");
	}

	/**
	 * Load bean definitions from the specified XML file.
	 *
	 * @param inputSource         the SAX InputSource to read from
	 * @param resourceDescription a description of the resource
	 *                            (can be {@code null} or empty)
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource, @Nullable String resourceDescription)
			throws BeanDefinitionStoreException {

		return doLoadBeanDefinitions(inputSource, new DescriptiveResource(resourceDescription));
	}

	/**
	 * 执行加载 Bean Definition 的真正逻辑
	 *
	 * @param inputSource
	 * @param resource
	 * @return
	 * @throws BeanDefinitionStoreException 主要做三件事：
	 *                                      1、调用 #getValidationModeForResource(Resource resource) 方法，获取指定资源（xml）的验证模式。
	 *                                      2、调用 DocumentLoader#loadDocument(InputSource inputSource, EntityResolver entityResolver,ErrorHandler errorHandler, int validationMode, boolean namespaceAware) 方法，获取 XML Document 实例。
	 *                                      3、调用 #registerBeanDefinitions(Document doc, Resource resource) 方法，根据获取的 Document 实例，注册 Bean 信息。
	 *                                      <p>
	 *                                      1、2都在 #doLoadDocument(inputSource, resource) 中
	 */
	protected int doLoadBeanDefinitions(InputSource inputSource, Resource resource) throws BeanDefinitionStoreException {
		try {
			// <1> 获取 XML Document 实例（详细解析见函数体内）
			Document doc = doLoadDocument(inputSource, resource);
			// <2> 根据 Document 实例，注册 BeanDefinitions（详细解析见函数体内）
			int count = registerBeanDefinitions(doc, resource);
			if (logger.isDebugEnabled()) {
				logger.debug("Loaded " + count + " bean definitions from " + resource);
			}
			return count;
		} catch (BeanDefinitionStoreException ex) {
			throw ex;
		} catch (SAXParseException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"Line " + ex.getLineNumber() + " in XML document from " + resource + " is invalid", ex);
		} catch (SAXException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"XML document from " + resource + " is invalid", ex);
		} catch (ParserConfigurationException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Parser configuration exception parsing XML from " + resource, ex);
		} catch (IOException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"IOException parsing XML document from " + resource, ex);
		} catch (Throwable ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Unexpected exception parsing XML document from " + resource, ex);
		}
		/**
		 * 在 <1> 处，调用 #doLoadDocument(InputSource inputSource, Resource resource) 方法，根据 xml 文件，获取 Document 实例。
		 * 在 <2> 处，调用 #registerBeanDefinitions(Document doc, Resource resource) 方法，根据获取的 Document 实例，注册 Bean 信息。
		 */
	}

	/**
	 * 获取 XML Document 实例
	 *
	 * @param inputSource
	 * @param resource
	 * @return
	 * @throws Exception
	 */
	protected Document doLoadDocument(InputSource inputSource, Resource resource) throws Exception {
		return this.documentLoader.loadDocument(inputSource, getEntityResolver(), this.errorHandler, getValidationModeForResource(resource), isNamespaceAware());
		/**
		 * 调用 DocumentLoader#loadDocument(InputSource inputSource, EntityResolver entityResolver, ErrorHandler errorHandler, int validationMode, boolean namespaceAware) 方法，获取 XML Document 实例，详细解析见函数体内；
		 * 该函数的五个参数分别对应：
		 * inputSource ：加载 Document 的 Resource 源。
		 * entityResolver ：解析文件的解析器。【重要】详细解析，见函数体内 #getEntityResolver()
		 * errorHandler ：处理加载 Document 对象的过程的错误。
		 * validationMode ：验证模式。【重要】详细解析，见函数体内（IoC 之获取验证模型）调用 #getValidationModeForResource(Resource resource) 方法，获取指定资源（xml）的验证模型；
		 * namespaceAware ：命名空间支持。如果要提供对 XML 名称空间的支持，则为 true 。
		 */
	}

	// 禁用验证模式
	public static final int VALIDATION_NONE = XmlValidationModeDetector.VALIDATION_NONE;
	// 自动获取验证模式
	public static final int VALIDATION_AUTO = XmlValidationModeDetector.VALIDATION_AUTO;
	// DTD 验证模式
	public static final int VALIDATION_DTD = XmlValidationModeDetector.VALIDATION_DTD;
	// XSD 验证模式
	public static final int VALIDATION_XSD = XmlValidationModeDetector.VALIDATION_XSD;
	// 验证模式。默认为自动模式。
	private int validationMode = VALIDATION_AUTO;

	/**
	 * Determine the validation mode for the specified {@link Resource}.
	 * If no explicit validation mode has been configured, then the validation
	 * mode gets {@link #detectValidationMode detected} from the given resource.
	 * <p>Override this method if you would like full control over the validation
	 * mode, even when something other than {@link #VALIDATION_AUTO} was set.
	 *
	 * @see #detectValidationMode
	 * <p>
	 * 《请先了解XSD与DTD的区别》
	 * 获取 xml 文件的验证模式；
	 * 为什么需要获取验证模式呢？
	 * XML 文件的验证模式保证了 XML 文件的正确性。
	 */
	protected int getValidationModeForResource(Resource resource) {
		// <1> 获取指定的验证模式
		int validationModeToUse = getValidationMode();
		// 首先，如果手动指定，则直接返回
		if (validationModeToUse != VALIDATION_AUTO) {
			return validationModeToUse;
		}
		// 其次，自动获取验证模式
		int detectedMode = detectValidationMode(resource);
		if (detectedMode != VALIDATION_AUTO) {
			return detectedMode;
		}
		// Hmm, we didn't get a clear indication... Let's assume XSD,
		// since apparently no DTD declaration has been found up until
		// detection stopped (before finding the document's root tag).
		// 最后，使用 VALIDATION_XSD 做为默认
		return VALIDATION_XSD;
		/**
		 * <1> 处，调用 #getValidationMode() 方法，获取指定的验证模式( validationMode )。如果有手动指定，则直接返回。另外，对于 validationMode 属性的设置和获得的代码，代码如下：
		 * public void setValidationMode(int validationMode) {
		 * 	  this.validationMode = validationMode;
		 * }
		 * public int getValidationMode() {
		 * 	  return this.validationMode;
		 * }
		 *
		 * <2> 处，调用 #detectValidationMode(Resource resource) 方法，自动获取验证模式。详细解析见函数体内
		 * <3> 处，使用 VALIDATION_XSD 做为默认。
		 */
	}

	/**
	 * XML 验证模式探测器
	 */
	private final XmlValidationModeDetector validationModeDetector = new XmlValidationModeDetector();

	/**
	 * Detect which kind of validation to perform on the XML file identified
	 * by the supplied {@link Resource}. If the file has a {@code DOCTYPE}
	 * definition then DTD validation is used otherwise XSD validation is assumed.
	 * <p>Override this method if you would like to customize resolution
	 * of the {@link #VALIDATION_AUTO} mode.
	 * <p>
	 * 自动获取验证模式
	 */
	protected int detectValidationMode(Resource resource) {
		// Resource 不可读，抛出 BeanDefinitionStoreException 异常
		if (resource.isOpen()) {
			throw new BeanDefinitionStoreException("Passed-in Resource [" + resource + "] contains an open stream: " + "cannot determine validation mode automatically. Either pass in a Resource " + "that is able to create fresh streams, or explicitly specify the validationMode " + "on your XmlBeanDefinitionReader instance.");
		}

		// 打开 InputStream 流
		InputStream inputStream;
		try {
			inputStream = resource.getInputStream();
		} catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"Unable to determine validation mode for [" + resource + "]: cannot open InputStream. " + "Did you attempt to load directly from a SAX InputSource without specifying the " + "validationMode on your XmlBeanDefinitionReader instance?", ex);
		}

		// <x> 获取相应的验证模式
		try {
			return this.validationModeDetector.detectValidationMode(inputStream);
		} catch (IOException ex) {
			throw new BeanDefinitionStoreException("Unable to determine validation mode for [" + resource + "]: an error occurred whilst reading from the InputStream.", ex);
		}
		/**
		 * 核心在于 <x> 处，调用 XmlValidationModeDetector#detectValidationMode(InputStream inputStream) 方法，获取相应的验证模式。
		 * 详细解析见函数体内
		 */
	}

	/**
	 * Register the bean definitions contained in the given DOM document.
	 * Called by {@code loadBeanDefinitions}.
	 * <p>Creates a new instance of the parser class and invokes
	 * {@code registerBeanDefinitions} on it.
	 *
	 * @param doc      the DOM document
	 * @param resource the resource descriptor (for context information)
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of parsing errors
	 * @see #loadBeanDefinitions
	 * @see #setDocumentReaderClass
	 * @see BeanDefinitionDocumentReader#registerBeanDefinitions
	 * <p>
	 * IoC 之注册 BeanDefinitions：XML Document => Bean Definition
	 */
	public int registerBeanDefinitions(Document doc, Resource resource) throws BeanDefinitionStoreException {
		// <1> 创建 BeanDefinitionDocumentReader 对象
		BeanDefinitionDocumentReader documentReader = createBeanDefinitionDocumentReader();
		// <2> 获取已注册的 BeanDefinition 数量
		int countBefore = getRegistry().getBeanDefinitionCount();
		// 两步：<3> 创建 XmlReaderContext 对象 <4> 注册 BeanDefinition，具体解析见函数体内
		documentReader.registerBeanDefinitions(doc, createReaderContext(resource));
		// <5> 返回新注册的 BeanDefinition 数量
		return getRegistry().getBeanDefinitionCount() - countBefore;
		/**
		 * <1> 处，调用 #createBeanDefinitionDocumentReader() 方法，实例化 BeanDefinitionDocumentReader 对象。<br>
		 * <2> 处，调用 BeanDefinitionRegistry#getBeanDefinitionCount() 方法，获取已注册的 BeanDefinition 数量。<br>
		 * <3> 处，调用 #createReaderContext(Resource resource) 方法，创建 XmlReaderContext 对象。<br>
		 * <4> 处，调用 BeanDefinitionDocumentReader#registerBeanDefinitions(Document doc, XmlReaderContext readerContext) 方法，读取 XML 元素，注册 BeanDefinition 们。<br>
		 * <5> 处，计算新注册的 BeanDefinition 数量。<br>
		 */
	}

	private Class<? extends BeanDefinitionDocumentReader> documentReaderClass = DefaultBeanDefinitionDocumentReader.class;

	/**
	 * 实例化 BeanDefinitionDocumentReader 对象<br>
	 * documentReaderClass 的默认值为 DefaultBeanDefinitionDocumentReader.class
	 *
	 * @return
	 */
	protected BeanDefinitionDocumentReader createBeanDefinitionDocumentReader() {
		return BeanUtils.instantiateClass(this.documentReaderClass);
	}

	/**
	 * 创建 XmlReaderContext 对象
	 *
	 * @param resource
	 * @return
	 */
	public XmlReaderContext createReaderContext(Resource resource) {
		return new XmlReaderContext(resource, this.problemReporter, this.eventListener,
				this.sourceExtractor, this, getNamespaceHandlerResolver());
	}

	/**
	 * Lazily create a default NamespaceHandlerResolver, if not set before.
	 *
	 * @see #createDefaultNamespaceHandlerResolver()
	 */
	public NamespaceHandlerResolver getNamespaceHandlerResolver() {
		if (this.namespaceHandlerResolver == null) {
			this.namespaceHandlerResolver = createDefaultNamespaceHandlerResolver();
		}
		return this.namespaceHandlerResolver;
	}

	/**
	 * Create the default implementation of {@link NamespaceHandlerResolver} used if none is specified.
	 * <p>The default implementation returns an instance of {@link DefaultNamespaceHandlerResolver}.
	 *
	 * @see DefaultNamespaceHandlerResolver#DefaultNamespaceHandlerResolver(ClassLoader)
	 */
	protected NamespaceHandlerResolver createDefaultNamespaceHandlerResolver() {
		ClassLoader cl = (getResourceLoader() != null ? getResourceLoader().getClassLoader() : getBeanClassLoader());
		return new DefaultNamespaceHandlerResolver(cl);
	}

}
