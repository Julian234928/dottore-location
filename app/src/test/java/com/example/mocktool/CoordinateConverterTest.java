package com.example.mocktool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * CoordinateConverter 的单元测试，纯 JVM 运行，不依赖 Android。
 *
 * 参考值由独立实现（Python）算出，不复用被测代码，
 * 因此这些断言可以真正校验转换结果，而不是"自己验证自己"。
 */
public class CoordinateConverterTest {

    /** 天安门 WGS84，公认的转换对照点。 */
    private static final double TM_LAT = 39.908692;
    private static final double TM_LON = 116.397477;

    // ---------- 境外判定 ----------

    @Test
    public void chinaPointsAreInside() {
        assertFalse(CoordinateConverter.outOfChina(39.908692, 116.397477)); // 北京
        assertFalse(CoordinateConverter.outOfChina(31.230416, 121.473701)); // 上海
        assertFalse(CoordinateConverter.outOfChina(23.106470, 113.324590)); // 广州
        assertFalse(CoordinateConverter.outOfChina(43.825592, 87.616848));  // 乌鲁木齐
    }

    @Test
    public void foreignPointsAreOutside() {
        assertTrue(CoordinateConverter.outOfChina(35.689487, 139.691711));  // 东京
        assertTrue(CoordinateConverter.outOfChina(48.856613, 2.352222));    // 巴黎
        assertTrue(CoordinateConverter.outOfChina(-33.868820, 151.209290)); // 悉尼
        assertTrue(CoordinateConverter.outOfChina(40.712776, -74.005974));  // 纽约
    }

    @Test
    public void boundaryIsExclusive() {
        // 边界值本身不算境外（判断用严格小于/大于）
        assertFalse(CoordinateConverter.outOfChina(55.8271, 137.8347));
        assertFalse(CoordinateConverter.outOfChina(0.8293, 72.004));
        assertTrue(CoordinateConverter.outOfChina(55.83, 116.0));
        assertTrue(CoordinateConverter.outOfChina(39.9, 72.0));
    }

    // ---------- 正向 WGS84 -> GCJ-02 ----------

    @Test
    public void wgs84ToGcj02MatchesReferenceValue() {
        double[] gcj = CoordinateConverter.wgs84ToGcj02(TM_LAT, TM_LON);
        // 独立算得 39.910095, 116.403721；公开资料常引 39.910124, 116.403687，
        // 差异源于不同实现的取舍，故容差取 1e-4 度（约 10 米）。
        assertEquals(39.910095, gcj[0], 1e-4);
        assertEquals(116.403721, gcj[1], 1e-4);
    }

    @Test
    public void domesticOffsetStaysInKnownRange() {
        // 国内偏移量公认在 300~800 米之间，这是最不依赖具体实现的不变量
        double[][] points = {
                {39.908692, 116.397477}, // 北京
                {31.230416, 121.473701}, // 上海
                {23.106470, 113.324590}, // 广州
                {30.657410, 104.065820}, // 成都
                {43.825592, 87.616848},  // 乌鲁木齐
        };
        for (double[] p : points) {
            double[] gcj = CoordinateConverter.wgs84ToGcj02(p[0], p[1]);
            double offset = CoordinateConverter.distanceMeters(p[0], p[1], gcj[0], gcj[1]);
            assertTrue("偏移过小: " + offset, offset > 200.0);
            assertTrue("偏移过大: " + offset, offset < 900.0);
        }
    }

    @Test
    public void foreignPointsAreNotShifted() {
        double[] tokyo = CoordinateConverter.wgs84ToGcj02(35.689487, 139.691711);
        assertArrayEquals(new double[]{35.689487, 139.691711}, tokyo, 1e-12);

        double[] paris = CoordinateConverter.wgs84ToGcj02(48.856613, 2.352222);
        assertArrayEquals(new double[]{48.856613, 2.352222}, paris, 1e-12);
    }

    // ---------- 反向 GCJ-02 -> WGS84 ----------

    @Test
    public void gcj02ToWgs84MatchesReferenceValue() {
        // 把上面算出的 GCJ-02 反解回 WGS84，应回到原点
        double[] wgs = CoordinateConverter.gcj02ToWgs84(39.910095, 116.403721);
        assertEquals(TM_LAT, wgs[0], 1e-5);
        assertEquals(TM_LON, wgs[1], 1e-5);
    }

    @Test
    public void roundTripErrorIsSubMeter() {
        double[][] points = {
                {39.908692, 116.397477},
                {31.230416, 121.473701},
                {23.106470, 113.324590},
                {30.657410, 104.065820},
                {43.825592, 87.616848},
        };
        for (double[] p : points) {
            double[] gcj = CoordinateConverter.wgs84ToGcj02(p[0], p[1]);
            double[] back = CoordinateConverter.gcj02ToWgs84(gcj[0], gcj[1]);
            double error = CoordinateConverter.distanceMeters(p[0], p[1], back[0], back[1]);
            assertTrue("往返误差过大: " + error + " 米", error < 0.01);
        }
    }

    @Test
    public void foreignGcj02IsNotShifted() {
        double[] tokyo = CoordinateConverter.gcj02ToWgs84(35.689487, 139.691711);
        assertArrayEquals(new double[]{35.689487, 139.691711}, tokyo, 1e-12);
    }

    // ---------- 唯一入口 toWgs84：防重复转换 ----------

    @Test
    public void wgs84InputPassesThroughUntouched() {
        // 输入本身就是 WGS84 时，必须原样返回，绝不能做任何转换
        double[] out = CoordinateConverter.toWgs84(TM_LAT, TM_LON, CoordinateConverter.SYS_WGS84);
        assertArrayEquals(new double[]{TM_LAT, TM_LON}, out, 0.0);
    }

    @Test
    public void gcj02InputIsConvertedOnce() {
        double[] out = CoordinateConverter.toWgs84(39.910095, 116.403721, CoordinateConverter.SYS_GCJ02);
        assertEquals(TM_LAT, out[0], 1e-5);
        assertEquals(TM_LON, out[1], 1e-5);

        // 关键：对同一份输入调用两次，结果必须完全相同，
        // 不存在"越转越偏"的累积效应
        double[] again = CoordinateConverter.toWgs84(39.910095, 116.403721, CoordinateConverter.SYS_GCJ02);
        assertArrayEquals(out, again, 0.0);
    }

    @Test
    public void repeatedWgs84PassThroughIsStable() {
        double lat = TM_LAT;
        double lon = TM_LON;
        for (int i = 0; i < 10; i++) {
            double[] out = CoordinateConverter.toWgs84(lat, lon, CoordinateConverter.SYS_WGS84);
            lat = out[0];
            lon = out[1];
        }
        assertEquals(TM_LAT, lat, 0.0);
        assertEquals(TM_LON, lon, 0.0);
    }

    // ---------- 距离公式 ----------

    @Test
    public void distanceOfSamePointIsZero() {
        assertEquals(0.0, CoordinateConverter.distanceMeters(TM_LAT, TM_LON, TM_LAT, TM_LON), 1e-9);
    }

    @Test
    public void distanceOneDegreeLatitudeIsAbout111km() {
        double d = CoordinateConverter.distanceMeters(0.0, 0.0, 1.0, 0.0);
        assertEquals(111195.0, d, 200.0);
    }

    @Test
    public void distanceBeijingToShanghaiIsAbout1070km() {
        double d = CoordinateConverter.distanceMeters(39.908692, 116.397477, 31.230416, 121.473701);
        assertEquals(1067000.0, d, 15000.0);
    }

    // ---------- 标签 ----------

    @Test
    public void systemLabelIsReadable() {
        assertEquals("WGS84", CoordinateConverter.systemLabel(CoordinateConverter.SYS_WGS84));
        assertEquals("GCJ-02", CoordinateConverter.systemLabel(CoordinateConverter.SYS_GCJ02));
        // 未知值按 WGS84 处理，不抛异常
        assertEquals("WGS84", CoordinateConverter.systemLabel(99));
    }
}
