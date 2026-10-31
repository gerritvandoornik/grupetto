package com.spop.poverlay.ride;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Minimal FIT (Flexible and Interoperable Data Transfer) encoder for an indoor cycling activity.
 *
 * Writes file_id, timer events, one record per sample, one lap, one session and the activity
 * message, which is what Strava, Garmin Connect and TrainingPeaks need to import a ride with
 * power, cadence, speed, distance and heart rate graphs.
 *
 * Plain Java with no Android dependencies so it can be unit tested on the JVM.
 */
public final class FitActivityWriter {

    /** One sample per second of timer time. */
    public static final class Sample {
        public final long timeMillis;   // wall clock, epoch ms
        public final int powerWatts;
        public final int cadenceRpm;
        public final float speedMps;
        public final int heartRateBpm;  // 0 = no reading

        public Sample(long timeMillis, int powerWatts, int cadenceRpm, float speedMps, int heartRateBpm) {
            this.timeMillis = timeMillis;
            this.powerWatts = powerWatts;
            this.cadenceRpm = cadenceRpm;
            this.speedMps = speedMps;
            this.heartRateBpm = heartRateBpm;
        }
    }

    /** Totals computed from the samples, also used for the ride list. */
    public static final class Summary {
        public long startMillis;
        public long endMillis;
        public int timerSeconds;
        public double distanceMeters;
        public long workJoules;
        public int calories;
        public int avgPower, maxPower, avgCadence, maxCadence, avgHeartRate, maxHeartRate;
        public double avgSpeedMps, maxSpeedMps;
    }

    // Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z).
    private static final long FIT_EPOCH_OFFSET = 631065600L;
    // A pause longer than this between samples is written as a timer stop/start.
    private static final long PAUSE_GAP_MILLIS = 2500L;
    // Same efficiency Grupetto uses for its on-screen calories.
    private static final double CYCLING_EFFICIENCY = 0.22;

    // Base types
    private static final int ENUM = 0x00, UINT8 = 0x02, UINT16 = 0x84, UINT32 = 0x86, UINT32Z = 0x8C;
    // Invalid values
    private static final int UINT8_INVALID = 0xFF;

    // Local message numbers
    private static final int L_FILE_ID = 0, L_EVENT = 1, L_RECORD = 2, L_LAP = 3, L_SESSION = 4, L_ACTIVITY = 5;

    private FitActivityWriter() {}

    public static Summary summarize(List<Sample> samples) {
        Summary s = new Summary();
        if (samples.isEmpty()) return s;
        s.startMillis = samples.get(0).timeMillis;
        s.endMillis = samples.get(samples.size() - 1).timeMillis;
        s.timerSeconds = samples.size();
        long powerSum = 0, cadenceSum = 0, cadenceCount = 0, hrSum = 0, hrCount = 0;
        double speedSum = 0;
        for (Sample x : samples) {
            s.distanceMeters += x.speedMps; // one second per sample
            powerSum += x.powerWatts;
            speedSum += x.speedMps;
            if (x.cadenceRpm > 0) { cadenceSum += x.cadenceRpm; cadenceCount++; }
            if (x.heartRateBpm > 0) { hrSum += x.heartRateBpm; hrCount++; }
            s.maxPower = Math.max(s.maxPower, x.powerWatts);
            s.maxCadence = Math.max(s.maxCadence, x.cadenceRpm);
            s.maxHeartRate = Math.max(s.maxHeartRate, x.heartRateBpm);
            s.maxSpeedMps = Math.max(s.maxSpeedMps, x.speedMps);
        }
        s.workJoules = powerSum;
        s.calories = (int) Math.round(powerSum / 4184.0 / CYCLING_EFFICIENCY);
        s.avgPower = (int) Math.round((double) powerSum / samples.size());
        s.avgSpeedMps = speedSum / samples.size();
        s.avgCadence = cadenceCount > 0 ? (int) Math.round((double) cadenceSum / cadenceCount) : 0;
        s.avgHeartRate = hrCount > 0 ? (int) Math.round((double) hrSum / hrCount) : 0;
        return s;
    }

    public static Summary write(List<Sample> samples, OutputStream out) throws IOException {
        if (samples.isEmpty()) throw new IllegalArgumentException("No samples");
        Summary sum = summarize(samples);

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Enc e = new Enc(data);

        long start = fitTime(sum.startMillis);
        long end = fitTime(sum.endMillis);

        // file_id (0)
        e.define(L_FILE_ID, 0, new int[][]{{0, 1, ENUM}, {1, 2, UINT16}, {2, 2, UINT16}, {3, 4, UINT32Z}, {4, 4, UINT32}});
        e.data(L_FILE_ID); e.u8(4); e.u16(255); e.u16(1); e.u32(start & 0xFFFFFFFFL | 1); e.u32(start);

        // event (21): timestamp, event, event_type, event_group
        e.define(L_EVENT, 21, new int[][]{{253, 4, UINT32}, {0, 1, ENUM}, {1, 1, ENUM}, {4, 1, UINT8}});
        writeEvent(e, start, 0);

        // record (20): timestamp, heart_rate, cadence, distance, speed, power
        e.define(L_RECORD, 20, new int[][]{{253, 4, UINT32}, {3, 1, UINT8}, {4, 1, UINT8}, {5, 4, UINT32}, {6, 2, UINT16}, {7, 2, UINT16}});
        double distance = 0;
        Sample prev = null;
        for (Sample x : samples) {
            if (prev != null && x.timeMillis - prev.timeMillis > PAUSE_GAP_MILLIS) {
                writeEvent(e, fitTime(prev.timeMillis), 4); // stop_all
                writeEvent(e, fitTime(x.timeMillis), 0);    // start
            }
            distance += x.speedMps;
            e.data(L_RECORD);
            e.u32(fitTime(x.timeMillis));
            e.u8(x.heartRateBpm > 0 ? clamp(x.heartRateBpm, 254) : UINT8_INVALID);
            e.u8(clamp(x.cadenceRpm, 254));
            e.u32(Math.round(distance * 100));
            e.u16(clamp(Math.round(x.speedMps * 1000), 65534));
            e.u16(clamp(x.powerWatts, 65534));
            prev = x;
        }

        writeEvent(e, end, 4); // stop_all

        long elapsedMs = (sum.endMillis - sum.startMillis) + 1000;
        long timerMs = sum.timerSeconds * 1000L;
        int hrAvg = sum.avgHeartRate > 0 ? sum.avgHeartRate : UINT8_INVALID;
        int hrMax = sum.maxHeartRate > 0 ? sum.maxHeartRate : UINT8_INVALID;

        // lap (19)
        e.define(L_LAP, 19, new int[][]{
                {253, 4, UINT32}, {254, 2, UINT16}, {0, 1, ENUM}, {1, 1, ENUM}, {2, 4, UINT32},
                {7, 4, UINT32}, {8, 4, UINT32}, {9, 4, UINT32}, {11, 2, UINT16},
                {13, 2, UINT16}, {14, 2, UINT16}, {15, 1, UINT8}, {16, 1, UINT8},
                {17, 1, UINT8}, {18, 1, UINT8}, {19, 2, UINT16}, {20, 2, UINT16},
                {25, 1, ENUM}, {39, 1, ENUM}});
        e.data(L_LAP);
        e.u32(end); e.u16(0); e.u8(9); e.u8(1); e.u32(start);
        e.u32(elapsedMs); e.u32(timerMs); e.u32(Math.round(sum.distanceMeters * 100)); e.u16(clamp(sum.calories, 65534));
        e.u16(clamp(Math.round(sum.avgSpeedMps * 1000), 65534)); e.u16(clamp(Math.round(sum.maxSpeedMps * 1000), 65534));
        e.u8(hrAvg); e.u8(hrMax);
        e.u8(clamp(sum.avgCadence, 254)); e.u8(clamp(sum.maxCadence, 254));
        e.u16(clamp(sum.avgPower, 65534)); e.u16(clamp(sum.maxPower, 65534));
        e.u8(2); e.u8(6); // cycling, indoor_cycling

        // session (18)
        e.define(L_SESSION, 18, new int[][]{
                {253, 4, UINT32}, {254, 2, UINT16}, {0, 1, ENUM}, {1, 1, ENUM}, {2, 4, UINT32},
                {5, 1, ENUM}, {6, 1, ENUM}, {7, 4, UINT32}, {8, 4, UINT32}, {9, 4, UINT32},
                {11, 2, UINT16}, {14, 2, UINT16}, {15, 2, UINT16}, {16, 1, UINT8}, {17, 1, UINT8},
                {18, 1, UINT8}, {19, 1, UINT8}, {20, 2, UINT16}, {21, 2, UINT16},
                {25, 2, UINT16}, {26, 2, UINT16}, {48, 4, UINT32}});
        e.data(L_SESSION);
        e.u32(end); e.u16(0); e.u8(8); e.u8(1); e.u32(start);
        e.u8(2); e.u8(6);
        e.u32(elapsedMs); e.u32(timerMs); e.u32(Math.round(sum.distanceMeters * 100));
        e.u16(clamp(sum.calories, 65534));
        e.u16(clamp(Math.round(sum.avgSpeedMps * 1000), 65534)); e.u16(clamp(Math.round(sum.maxSpeedMps * 1000), 65534));
        e.u8(hrAvg); e.u8(hrMax);
        e.u8(clamp(sum.avgCadence, 254)); e.u8(clamp(sum.maxCadence, 254));
        e.u16(clamp(sum.avgPower, 65534)); e.u16(clamp(sum.maxPower, 65534));
        e.u16(0); e.u16(1); e.u32(sum.workJoules);

        // activity (34): timestamp, total_timer_time, num_sessions, type, event, event_type, local_timestamp
        int tzOffsetSec = java.util.TimeZone.getDefault().getOffset(sum.endMillis) / 1000;
        e.define(L_ACTIVITY, 34, new int[][]{{253, 4, UINT32}, {0, 4, UINT32}, {1, 2, UINT16}, {2, 1, ENUM}, {3, 1, ENUM}, {4, 1, ENUM}, {5, 4, UINT32}});
        e.data(L_ACTIVITY);
        e.u32(end); e.u32(timerMs); e.u16(1); e.u8(0); e.u8(26); e.u8(1); e.u32(end + tzOffsetSec);

        byte[] body = data.toByteArray();

        // 14-byte header
        byte[] header = new byte[14];
        header[0] = 14;
        header[1] = 0x10;                 // protocol 1.0
        int profile = 2132;               // profile 21.32
        header[2] = (byte) profile; header[3] = (byte) (profile >> 8);
        header[4] = (byte) body.length; header[5] = (byte) (body.length >> 8);
        header[6] = (byte) (body.length >> 16); header[7] = (byte) (body.length >> 24);
        header[8] = '.'; header[9] = 'F'; header[10] = 'I'; header[11] = 'T';
        int hcrc = crc(0, header, 0, 12);
        header[12] = (byte) hcrc; header[13] = (byte) (hcrc >> 8);

        int fileCrc = crc(0, header, 0, 14);
        fileCrc = crc(fileCrc, body, 0, body.length);

        out.write(header);
        out.write(body);
        out.write(fileCrc & 0xFF);
        out.write((fileCrc >> 8) & 0xFF);
        out.flush();
        return sum;
    }

    private static void writeEvent(Enc e, long fitTs, int eventType) throws IOException {
        e.data(L_EVENT);
        e.u32(fitTs); e.u8(0); e.u8(eventType); e.u8(0);
    }

    private static long fitTime(long epochMillis) {
        return epochMillis / 1000L - FIT_EPOCH_OFFSET;
    }

    private static int clamp(long v, int max) {
        return (int) Math.max(0, Math.min(max, v));
    }

    private static final int[] CRC_TABLE = {
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    };

    static int crc(int crc, byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int b = buf[i] & 0xFF;
            int tmp = CRC_TABLE[crc & 0xF];
            crc = (crc >> 4) & 0x0FFF;
            crc = crc ^ tmp ^ CRC_TABLE[b & 0xF];
            tmp = CRC_TABLE[crc & 0xF];
            crc = (crc >> 4) & 0x0FFF;
            crc = crc ^ tmp ^ CRC_TABLE[(b >> 4) & 0xF];
        }
        return crc & 0xFFFF;
    }

    /** Little-endian writer for definition and data messages. */
    private static final class Enc {
        private final OutputStream out;
        Enc(OutputStream out) { this.out = out; }

        void define(int local, int global, int[][] fields) throws IOException {
            out.write(0x40 | local);
            out.write(0);       // reserved
            out.write(0);       // little endian
            u16(global);
            out.write(fields.length);
            for (int[] f : fields) { out.write(f[0]); out.write(f[1]); out.write(f[2]); }
        }

        void data(int local) throws IOException { out.write(local & 0x0F); }
        void u8(int v) throws IOException { out.write(v & 0xFF); }
        void u16(long v) throws IOException { out.write((int) (v & 0xFF)); out.write((int) ((v >> 8) & 0xFF)); }
        void u32(long v) throws IOException {
            out.write((int) (v & 0xFF)); out.write((int) ((v >> 8) & 0xFF));
            out.write((int) ((v >> 16) & 0xFF)); out.write((int) ((v >> 24) & 0xFF));
        }
    }
}
