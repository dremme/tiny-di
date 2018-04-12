# TinyInjector

TinyInjector aims to be a very light weight dependency injection library. For that reason it is written in Java and has zero dependencies.

It only uses constructor dependency injection for that.

The class-path is automatically being searched for classes annotated with an annotation of your choice, although they all have to use the same annotation. All matching classes are topologically sorted and instantiated.

## Example

```java
@Component public class Foo() {
    public void beep() { ... }
}

@Component public class Bar(Foo foo) { ... }
```

To automatically scan for these classes just call:

```java
import static hamburg.remme.tinyinjector.Injector.scan;
import hamburg.remme.tinyinjector.Component;

public static final main(String[] args) {
    scan(Component.class, "my.package");
}
```

A `Map` of singletons is being created as a result.

To retrieve a singleton just call:

```java
import static hamburg.remme.tinyinjector.Injector.scan;
import hamburg.remme.tinyinjector.Component;

public static final main(String[] args) {
    scan(Component.class, "my.package");
    var foo = retrieve(Foo.class);
    foo.beep();
}
```
