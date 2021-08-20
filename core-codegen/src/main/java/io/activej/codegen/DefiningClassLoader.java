/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.codegen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.activej.codegen.util.Utils.getPathSetting;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/**
 * Represents a loader for defining dynamically generated classes.
 * Also contains cache, that speeds up loading of classes, which have the same structure as the ones already loaded.
 */
@SuppressWarnings("WeakerAccess")
public final class DefiningClassLoader extends ClassLoader implements DefiningClassLoaderMBean {
	public static final Path DEFAULT_DEBUG_OUTPUT_DIR = getPathSetting(ClassBuilder.class, "debugOutputDir", null);

	private final Map<String, Class<?>> definedClasses = new ConcurrentHashMap<>();
	private final Map<ClassKey<?>, Class<?>> cachedClasses = new ConcurrentHashMap<>();

	@Nullable
	private BytecodeStorage bytecodeStorage;

	private Path debugOutputDir = DEFAULT_DEBUG_OUTPUT_DIR;

	// region builders
	private DefiningClassLoader() {
	}

	private DefiningClassLoader(ClassLoader parent) {
		super(parent);
	}

	public static DefiningClassLoader create() {
		return new DefiningClassLoader();
	}

	public static DefiningClassLoader create(ClassLoader parent) {
		return new DefiningClassLoader(parent);
	}
	// endregion

	public DefiningClassLoader withBytecodeStorage(BytecodeStorage bytecodeStorage) {
		this.bytecodeStorage = bytecodeStorage;
		return this;
	}

	public DefiningClassLoader withDebugOutputDir(Path debugOutputDir) {
		this.debugOutputDir = debugOutputDir;
		return this;
	}

	public Class<?> defineClass(String className, byte[] bytecode) {
		Class<?> aClass = super.defineClass(className, bytecode, 0, bytecode.length);
		definedClasses.put(className, aClass);
		if (debugOutputDir != null) {
			try (FileOutputStream fos = new FileOutputStream(debugOutputDir.resolve(className + ".class").toFile())) {
				fos.write(bytecode);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return aClass;
	}

	@NotNull
	public <T> Class<T> ensureClass(String className, Supplier<ClassBuilder<T>> classBuilder) {
		return ensureClass(className, (cl, s) -> classBuilder.get().toBytecode(cl, s));
	}

	@NotNull
	public <T> Class<T> ensureClass(ClassKey<T> key, Supplier<ClassBuilder<T>> classBuilder) {
		return ensureClass(key, classLoader -> classBuilder.get().toBytecode(classLoader));
	}

	@NotNull
	public <T> T ensureClassAndCreateInstance(ClassKey<T> key, Supplier<ClassBuilder<T>> classBuilder,
			Object... arguments) {
		return ensureClassAndCreateInstance(key, classLoader -> classBuilder.get().toBytecode(classLoader), arguments);
	}

	@SuppressWarnings("unchecked")
	@NotNull
	public <T> Class<T> ensureClass(String className, BiFunction<ClassLoader, String, GeneratedBytecode> bytecodeBuilder) {
		synchronized (getClassLoadingLock(className)) {
			Class<?> aClass = findLoadedClass(className);
			if (aClass != null) return (Class<T>) aClass;
			byte[] bytecode;
			if (bytecodeStorage != null) {
				try {
					bytecode = bytecodeStorage.loadBytecode(className).orElse(null);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				if (bytecode != null) {
					return (Class<T>) defineClass(className, bytecode);
				}
			}

			GeneratedBytecode generatedBytecode = bytecodeBuilder.apply(this, className);
			aClass = generatedBytecode.defineClass(this);

			if (bytecodeStorage != null) {
				try {
					bytecodeStorage.saveBytecode(className, generatedBytecode.getBytecode());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			return (Class<T>) aClass;
		}
	}

	@NotNull
	public <T> Class<T> ensureClass(ClassKey<T> key, Function<ClassLoader, GeneratedBytecode> bytecodeBuilder) {
		//noinspection unchecked
		return (Class<T>) cachedClasses.computeIfAbsent(key, k -> bytecodeBuilder.apply(this).defineClass(this));
	}

	@NotNull
	public <T> T ensureClassAndCreateInstance(ClassKey<T> key, Function<ClassLoader, GeneratedBytecode> bytecodeBuilder,
			Object... arguments) {
		try {
			return ensureClass(key, bytecodeBuilder)
					.getConstructor(Arrays.stream(arguments).map(Object::getClass).toArray(Class<?>[]::new))
					.newInstance(arguments);
		} catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	// JMX

	@Override
	public int getDefinedClassesCount() {
		return definedClasses.size();
	}

	@Override
	public Map<String, Long> getDefinedClassesCountByType() {
		return definedClasses.values().stream()
				.map(aClass -> aClass.getSuperclass() == Object.class && aClass.getInterfaces().length != 0 ?
						aClass.getInterfaces()[0] :
						aClass.getSuperclass())
				.map(Class::getName)
				.collect(groupingBy(identity(), counting()));
	}

	@Nullable
	public Class<?> getCachedClass(@NotNull ClassKey<?> key) {
		return cachedClasses.get(key);
	}

	@Override
	public int getCachedClassesCount() {
		return cachedClasses.size();
	}

	@Override
	public Map<String, Long> getCachedClassesCountByType() {
		return cachedClasses.keySet().stream()
				.map(key -> key.getKeyClass().getName())
				.collect(groupingBy(identity(), counting()));
	}

	@Override
	public String toString() {
		return "{classes=" + cachedClasses.size() + ", byType=" + getCachedClassesCountByType() + '}';
	}
}
