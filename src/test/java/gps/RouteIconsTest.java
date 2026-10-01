package gps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import javax.swing.ImageIcon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The panel's icons are PNG resources under /icons (0.14.0 moved them out of Java2D code, which
 * the hub's review bot counted). route-icons.sha256 records every icon's pixels as they were
 * drawn, so the files must stay what the code produced, pixel for pixel. To accept a deliberate
 * change to an icon, re-record with -Dgps.recordIcons=true (the test then rewrites the file).
 */
public class RouteIconsTest
{
	private static final String HASHES = "src/test/resources/route-icons.sha256";

	@Test
	public void everyIconHasItsRecordedPixels() throws Exception
	{
		Map<String, BufferedImage> icons = allIcons();
		if (System.getProperty("gps.recordIcons") != null)
		{
			List<String> lines = new ArrayList<>();
			for (Map.Entry<String, BufferedImage> e : icons.entrySet())
			{
				lines.add(e.getKey() + "\t" + pixelHash(e.getValue()));
			}
			Files.write(Paths.get(HASHES), lines, StandardCharsets.UTF_8);
			return;
		}
		Map<String, String> expected = recorded();
		assertFalse("no recorded icons", expected.isEmpty());
		for (Map.Entry<String, String> e : expected.entrySet())
		{
			BufferedImage icon = icons.get(e.getKey());
			assertNotNull("recorded icon " + e.getKey() + " no longer exists", icon);
			assertEquals(e.getKey() + " changed pixels (re-record with -Dgps.recordIcons=true if intended)",
				e.getValue(), pixelHash(icon));
		}
		for (String name : icons.keySet())
		{
			assertTrue(name + " is not recorded: run with -Dgps.recordIcons=true", expected.containsKey(name));
		}
	}

	@Test
	public void iconsAreSixteenPixelSquares() throws Exception
	{
		for (Map.Entry<String, BufferedImage> e : allIcons().entrySet())
		{
			assertEquals(e.getKey() + " width", 16, e.getValue().getWidth());
			assertEquals(e.getKey() + " height", 16, e.getValue().getHeight());
		}
	}

	/**
	 * Every icon the class exposes, by field name (array entries as NAME_1, NAME_2, ...), plus the
	 * sidebar pin. Private destination icons are included: they reach the panel via
	 * {@link RouteIcons#destinationIcon}.
	 */
	static Map<String, BufferedImage> allIcons() throws Exception
	{
		Map<String, BufferedImage> icons = new LinkedHashMap<>();
		for (Field field : RouteIcons.class.getDeclaredFields())
		{
			if (!Modifier.isStatic(field.getModifiers()))
			{
				continue;
			}
			field.setAccessible(true);
			Object value = field.get(null);
			if (value instanceof ImageIcon)
			{
				icons.put(field.getName(), toBuffered((ImageIcon) value));
			}
			else if (value instanceof ImageIcon[])
			{
				ImageIcon[] array = (ImageIcon[]) value;
				for (int i = 0; i < array.length; i++)
				{
					icons.put(field.getName() + "_" + (i + 1), toBuffered(array[i]));
				}
			}
		}
		icons.put("gpsPin", RouteIcons.gpsPin());
		return icons;
	}

	private static BufferedImage toBuffered(ImageIcon icon)
	{
		return (BufferedImage) icon.getImage();
	}

	/** SHA-256 of the ARGB pixels in row-major order, as hex. */
	static String pixelHash(BufferedImage image) throws Exception
	{
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				int argb = image.getRGB(x, y);
				digest.update(new byte[]{(byte) (argb >>> 24), (byte) (argb >>> 16), (byte) (argb >>> 8), (byte) argb});
			}
		}
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest())
		{
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static Map<String, String> recorded() throws IOException
	{
		Map<String, String> expected = new LinkedHashMap<>();
		try (InputStream in = RouteIconsTest.class.getResourceAsStream("/route-icons.sha256"))
		{
			assertNotNull("route-icons.sha256 is missing from the test resources", in);
			Scanner scanner = new Scanner(in, "UTF-8");
			while (scanner.hasNextLine())
			{
				String[] fields = scanner.nextLine().split("\t");
				if (fields.length == 2)
				{
					expected.put(fields[0], fields[1]);
				}
			}
		}
		return expected;
	}
}
