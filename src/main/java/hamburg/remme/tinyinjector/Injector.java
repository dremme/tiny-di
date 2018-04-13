package hamburg.remme.tinyinjector;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * TinyInjector aims to be a very light weight dependency injection library. For that reason it is written in Java and
 * has zero dependencies.
 * <p>
 * It only uses constructor dependency injection for that.
 * <p>
 * The class-path is automatically being searched for classes annotated with an annotation of your choice, although they
 * all have to use the same annotation. All matching classes are topologically sorted and instantiated.
 *
 * <h2>Usage:</h2>
 *
 * <pre>
 * {@literal @}Component public class Foo {
 *     public void beep() { ... }
 * }
 *
 * {@literal @}Component public class Bar { public Bar(Foo foo) { ... } }
 * </pre>
 * <p>
 * To automatically scan for these classes just call:
 *
 * <pre>
 * import  hamburg.remme.tinyinjector.Injector.scan;
 * import hamburg.remme.tinyinjector.Component;
 *
 * public static void main(String[] args) {
 *     scan(Component.class, "my.package");
 * }
 * </pre>
 * <p>
 * A {@code Map} of singletons is being created as a result.
 * <p>
 * To retrieve a singleton just call:
 *
 * <pre>
 * import  hamburg.remme.tinyinjector.Injector.scan;
 * import hamburg.remme.tinyinjector.Component;
 *
 * public static void main(String[] args) {
 *     scan(Component.class, "my.package");
 *     var foo = retrieve(Foo.class);
 *     foo.beep();
 * }
 * </pre>
 *
 * @author Dennis Remme (dennis@remme.hamburg)
 * @todo: migrate to Java 10 asap; depends on JITPACK and Lombok
 * @todo: move to MavenCentral? Bit cumbersome...
 */
@UtilityClass public class Injector {

    private final String CLASS_EXTENSION = ".class";
    private Map<Class<?>, Object> DEPENDENCY_MAP;

    /**
     * Scans the class-path for classes annotated with {@link Component}, but only if they are inside the given
     * package.
     * <p>
     * A {@link Map} of singletons is created as a result.
     *
     * @throws IllegalArgumentException when there are cyclic or missing dependencies
     * @throws IllegalStateException    when the class-path has already been scanned
     * @see #retrieve(Class)
     */
    public void scan(@NonNull String packageName) {
        scan(Component.class, packageName);
    }

    /**
     * Scans the class-path for classes annotated with the given class, but only if they are inside the given package.
     * <p>
     * A {@link Map} of singletons is created as a result.
     *
     * @throws IllegalArgumentException when there are cyclic or missing dependencies
     * @throws IllegalStateException    when the class-path has already been scanned
     * @see #retrieve(Class)
     */
    public void scan(@NonNull Class<? extends Annotation> annotationClass, @NonNull String packageName) {
        if (DEPENDENCY_MAP != null) throw new IllegalStateException("Class-path has already been scanned.");

        val basePath = packageName.replace('.', '/');
        val classLoader = Thread.currentThread().getContextClassLoader();

        // Scan classes with attached annotation
        val classNames = isExecutingJar(classLoader) ? scanJar(basePath, classLoader) : scanFiles(basePath, classLoader);
        val classes = classNames.map(it -> substringAfter(it, basePath).replace('/', '.'))
                .map(it -> packageName + "." + it)
                .map(it -> it.substring(0, it.length() - CLASS_EXTENSION.length()))
                .<Class<?>>map(it -> asClass(it, classLoader)) // type declaration needed!
                .filter(it -> it.isAnnotationPresent(annotationClass));

        // Create graph nodes
        val graph = new HashSet<ClassNode>();
        classes.forEach(clazz -> getOrCreate(graph, clazz).neighbors
                .addAll(primaryParameters(clazz).stream()
                        .map(it -> getOrCreate(graph, it.getType()))
                        .collect(toList())));
        val indegree = new HashMap<ClassNode, Integer>();
        graph.forEach(it -> indegree.put(it, 0));
        graph.stream().flatMap(it -> it.neighbors.stream()).forEach(it -> indegree.put(it, indegree.get(it) + 1));

        // Topologically sort the nodes
        val sorted = new ArrayList<Class<?>>();
        val queue = new LinkedList<ClassNode>();

        graph.stream().filter(it -> indegree.get(it) == 0).forEach(it -> {
            queue.offer(it);
            sorted.add(it.value);
        });

        while (!queue.isEmpty()) {
            queue.poll().neighbors.forEach(it -> {
                indegree.put(it, indegree.get(it) - 1);
                if (indegree.get(it) == 0) {
                    queue.offer(it);
                    sorted.add(0, it.value);
                }
            });
        }

        if (sorted.size() != graph.size()) throw new RuntimeException("Cyclic dependencies detected.");

        // Instantiate
        DEPENDENCY_MAP = new HashMap<>();
        sorted.forEach(clazz -> {
            try {
                val arguments = primaryParameters(clazz).stream().map(it -> DEPENDENCY_MAP.get(it.getType())).toArray();
                DEPENDENCY_MAP.put(clazz, primaryConstructor(clazz).newInstance(arguments));
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("Missing dependency.", e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException("Injecting dependecies failed for class " + clazz);
            }
        });
    }

    private Stream<String> scanJar(String basePath, ClassLoader classLoader) {
        return asStream(getJar(classLoader).entries())
                .map(ZipEntry::getName)
                .filter(it -> it.startsWith(basePath))
                .filter(it -> it.toLowerCase().endsWith(CLASS_EXTENSION));
    }

    @SneakyThrows private Stream<String> scanFiles(String basePath, ClassLoader classLoader) {
        return asStream(classLoader.getResources(basePath))
                .map(URL::getFile)
                .map(Paths::get)
                .flatMap(Injector::walk)
                .map(Path::toString)
                .filter(it -> it.toLowerCase().endsWith(CLASS_EXTENSION));
    }

    private ClassNode getOrCreate(Set<ClassNode> graph, Class<?> clazz) {
        return graph.stream().filter(it -> it.value.equals(clazz)).findAny().orElseGet(() -> {
            val node = new ClassNode(clazz);
            graph.add(node);
            return node;
        });
    }

    private Constructor primaryConstructor(Class<?> clazz) {
        return clazz.getDeclaredConstructors()[0];
    }

    private List<Parameter> primaryParameters(Class<?> clazz) {
        return Arrays.asList(primaryConstructor(clazz).getParameters());
    }

    /**
     * Retrieves a singleton instance of the class.
     *
     * @throws IllegalStateException    when no classes have been scanned yet
     * @throws IllegalArgumentException when the class has not been scanned
     */
    @SuppressWarnings("unchecked") public <T> T retrieve(@NonNull Class<T> clazz) {
        if (DEPENDENCY_MAP == null) throw new IllegalStateException("Retrieve called before scanning class-path.");
        if (!DEPENDENCY_MAP.containsKey(clazz)) throw new IllegalArgumentException("No such singleton.");
        return (T) DEPENDENCY_MAP.get(clazz);
    }

    private boolean isExecutingJar(ClassLoader classLoader) {
        return classLoader.getResource("").toExternalForm().startsWith("jar");
    }

    @SneakyThrows private JarFile getJar(ClassLoader classLoader) {
        return new JarFile(new File(classLoader.getResource("").toURI()));
    }

    @SneakyThrows private Stream<Path> walk(Path path) {
        return Files.walk(path);
    }

    private String substringAfter(String source, String delimiter) {
        return source.substring(source.lastIndexOf(delimiter) + delimiter.length() + 1);
    }

    private <T> Stream<T> asStream(Enumeration<T> enumeration) {
        return stream(spliteratorUnknownSize(EnumIterator.of(enumeration), ORDERED), false);
    }

    @SneakyThrows private Class<?> asClass(String className, ClassLoader classLoader) {
        return Class.forName(className, false, classLoader);
    }

    /**
     * Used for topological sort.
     */
    @RequiredArgsConstructor class ClassNode {

        final Class<?> value;
        final List<ClassNode> neighbors = new ArrayList<>();

    }

    /**
     * Used to wrap {@link Enumeration}.
     *
     * @deprecated To be replaced with Java 10.
     */
    @RequiredArgsConstructor(staticName = "of") class EnumIterator<T> implements Iterator<T> {

        final Enumeration<T> enumeration;

        @Override public boolean hasNext() {
            return enumeration.hasMoreElements();
        }

        @Override public T next() {
            return enumeration.nextElement();
        }

    }

}
