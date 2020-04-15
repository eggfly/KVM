package eggfly.kvm.demo;

import java.util.Objects;

public class AddingTest {
    public int add(int a, int b) {
        return a + b;
    }

    public int addThis(int a, int b) {
        return this.add(1, 2) + a + b;
    }

    public void functionA() {
        // some code without function call
        int result = add(2, 3); //call to function B
        // some code without function call
    }

    public void functionB() {
        testLongParams(0, 0, 0, 0, "", null, null);
    }

    public long testLongParams(double a, float b, long c, int d, String e, Object[] f, Class g) {
        // some code without function call
        int result = add(2, 3); //call to function B
        // some code without function call
        return result;
    }

}
