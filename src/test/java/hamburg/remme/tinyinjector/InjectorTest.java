package hamburg.remme.tinyinjector;

import hamburg.remme.tinyinjector.test.Bar;
import hamburg.remme.tinyinjector.test.Foo;
import hamburg.remme.tinyinjector.test.TestAnn;

import lombok.val;
import org.junit.jupiter.api.Test;

class InjectorTest {

    @Test void shouldScan() {
        // Given
        val packageName = "hamburg.remme.tinyinjector.test";
        val annotationClass = TestAnn.class;

        // When
        Injector.scan(annotationClass, packageName);

        // Then
        Injector.retrieve(Foo.class);
        Injector.retrieve(Bar.class).beep();
    }

}
