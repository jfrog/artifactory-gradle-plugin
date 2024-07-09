package org.gradle;

import org.gradle.Child;
import org.junit.Assert;

public class Consumer {
    public static void main() {
        Assert.assertTrue(Child.isChild());
    }
}
