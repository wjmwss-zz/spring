/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.core.testfixture;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * {@code @EnabledForTestGroups} is used to enable the annotated test class or
 * test method for one or more {@link TestGroup} {@linkplain #value values}.
 *
 * @author Sam Brannen
 * @since 5.2
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(TestGroupsCondition.class)
public @interface EnabledForTestGroups {

	/**
	 * One or more {@link TestGroup}s that must be active.
	 */
	TestGroup[] value();

}
