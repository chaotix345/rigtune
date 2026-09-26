import io.github.chaotix345.rigtune.core.hardware.DriverVersionParser;
import io.github.chaotix345.rigtune.core.model.DriverVersion;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;

import java.util.Arrays;

// AC9.8: parse real driver strings with the RC's own parser. args: <vendor> <backend> <raw...>
public class ParseDriver {
	public static void main(String[] a) {
		GpuVendor vendor = GpuVendor.valueOf(a[0]);
		GraphicsBackend backend = GraphicsBackend.valueOf(a[1]);
		String raw = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
		DriverVersion v = DriverVersionParser.parse(vendor, backend, raw);
		System.out.println("raw=\"" + raw + "\" vendor=" + vendor + " backend=" + backend + " -> family=" + v.family() + " comparable="
				+ Arrays.toString(v.comparable()) + " display=" + v.display());
	}
}
