package com.example.mocktool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** CoordinateValidator 的单元测试，纯 JVM 运行，不依赖 Android。 */
public class CoordinateValidatorTest {

    @Test
    public void normalInputIsAccepted() {
        CoordinateValidator.Result r =
                CoordinateValidator.parse("39.908692", "116.397477");
        assertTrue(r.valid);
        assertEquals(39.908692, r.latitude, 1e-9);
        assertEquals(116.397477, r.longitude, 1e-9);
    }

    @Test
    public void negativeCoordinatesAreAccepted() {
        CoordinateValidator.Result r =
                CoordinateValidator.parse("-33.868820", "-151.209290");
        assertTrue(r.valid);
        assertEquals(-33.868820, r.latitude, 1e-9);
        assertEquals(-151.209290, r.longitude, 1e-9);
    }

    @Test
    public void boundaryValuesAreAccepted() {
        assertTrue(CoordinateValidator.parse("90", "180").valid);
        assertTrue(CoordinateValidator.parse("-90", "-180").valid);
        assertTrue(CoordinateValidator.parse("0", "0").valid);
    }

    @Test
    public void latitudeOutOfRangeIsRejected() {
        CoordinateValidator.Result r = CoordinateValidator.parse("90.0001", "0");
        assertFalse(r.valid);
        assertNotNull(r.error);
    }

    @Test
    public void longitudeOutOfRangeIsRejected() {
        CoordinateValidator.Result r = CoordinateValidator.parse("0", "180.0001");
        assertFalse(r.valid);
        assertNotNull(r.error);
    }

    @Test
    public void emptyInputIsRejected() {
        assertFalse(CoordinateValidator.parse("", "116.4").valid);
        assertFalse(CoordinateValidator.parse("39.9", "   ").valid);
    }

    @Test
    public void nullInputIsRejected() {
        assertFalse(CoordinateValidator.parse(null, null).valid);
        assertFalse(CoordinateValidator.parse("39.9", null).valid);
    }

    @Test
    public void nonNumericInputIsRejected() {
        assertFalse(CoordinateValidator.parse("abc", "116.4").valid);
        assertFalse(CoordinateValidator.parse("39.9", "116.4E").valid);
        assertFalse(CoordinateValidator.parse("39.9.9", "116.4").valid);
    }

    @Test
    public void lenientJavaSyntaxIsRejected() {
        // Double.parseDouble 会接受这些，但不应被当成合法坐标
        assertFalse(CoordinateValidator.parse("1d", "0").valid);
        assertFalse(CoordinateValidator.parse("0x1p3", "0").valid);
    }

    @Test
    public void whitespaceIsTrimmed() {
        CoordinateValidator.Result r = CoordinateValidator.parse("  39.9  ", " 116.4 ");
        assertTrue(r.valid);
        assertEquals(39.9, r.latitude, 1e-9);
        assertEquals(116.4, r.longitude, 1e-9);
    }

    @Test
    public void scientificNotationIsAccepted() {
        CoordinateValidator.Result r = CoordinateValidator.parse("3.99e1", "1.164e2");
        assertTrue(r.valid);
        assertEquals(39.9, r.latitude, 1e-6);
        assertEquals(116.4, r.longitude, 1e-6);
    }
}
