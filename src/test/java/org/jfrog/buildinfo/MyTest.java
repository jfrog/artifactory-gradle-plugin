package org.jfrog.buildinfo;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

public class MyTest {

    @BeforeMethod
    public void setup() {
        System.out.println("Running tests");
    }

    @Test
    public void testAssertingTrue() {
        assertTrue(false);
    }
}
