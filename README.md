# spring源码学习 
>版本：Spring 5.3.0.RELEASE，基于 Gradle 构建，调试入口分为两个：
>*  1、XmlBeanDefinitionReaderTests #withImport()，提供了 IOC Bean 装载阶段调试，即 XML Resource => XML Document； 
>*  2、XmlBeanCollectionTests #testRefSubelement()，提供了 IOC Bean 注册阶段的调试，即 XML Document => Bean Definition；   

>代码中已经包含了所有的中文注释，直接调试即可；