package hamburg.remme.tinyinjector;

import static hamburg.remme.tinyinjector.Injector.*;

import hamburg.remme.tinyinjector.test.Bar;
import hamburg.remme.tinyinjector.test.Baz;
import hamburg.remme.tinyinjector.test.Foo;
import hamburg.remme.tinyinjector.test.TestAnn;

import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InjectorTest {

    @Test void shouldScan() {
        // Given
        val packageName = "hamburg.remme.tinyinjector.test";
        val annotationClass = TestAnn.class;

        // When
        scan(annotationClass, packageName);

        // Then
        retrieve(Foo.class);
        retrieve(Bar.class).beep();
    }

    @Test void shouldInject() {
        // Given
        scan(TestAnn.class, "hamburg.remme.tinyinjector.test");
        val clazz = Baz.class;

        // When
        val baz = inject(clazz);

        // Then
        baz.getBar().beep();
    }

    @AfterEach void tearDown() {
        clear();
    }

}
