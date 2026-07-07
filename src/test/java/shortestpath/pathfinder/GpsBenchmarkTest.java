package shortestpath.pathfinder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.AlternativeRoutesService;
import shortestpath.GpsBenchmark;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;

/**
 * Covers the fixed benchmark harness: one scenario through the real generation pipeline must
 * produce a JSON report with the pinned parameters, per-run metrics, a median, and a route
 * fingerprint — the artifact used to compare profiling data across plugin versions.
 */
@RunWith(MockitoJUnitRunner.class)
public class GpsBenchmarkTest
{
	private static final int START = WorldPointUtil.packWorldPoint(3222, 3218, 0);   // Lumbridge
	private static final int TARGET = WorldPointUtil.packWorldPoint(3213, 3424, 0);  // Varrock centre

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Mock
	Client client;
	@Mock
	ClientThread clientThread;
	@Mock
	net.runelite.api.ItemContainer inventory;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useTeleportationSpells()).thenReturn(true);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(invocation -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(new Item[]{
			new Item(ItemID.LAWRUNE, 10),
			new Item(ItemID.AIRRUNE, 30),
			new Item(ItemID.FIRERUNE, 10),
		}).when(inventory).getItems();
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
	}

	@Test
	public void benchmarkProducesComparableReport() throws Exception
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);
		File outputDir = temp.newFolder("gps-debug");

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> lastMessage = new AtomicReference<>("");
		GpsBenchmark benchmark = new GpsBenchmark(service,
			List.of(GpsBenchmark.Scenario.trip("Lumbridge -> Varrock", START, TARGET)),
			outputDir, new Gson(), lastMessage::set, done::countDown);
		benchmark.start();

		assertTrue("Benchmark should complete", done.await(300, TimeUnit.SECONDS));
		service.shutdown();

		File[] reports = outputDir.listFiles((dir, name) -> name.startsWith("gps-benchmark-"));
		assertTrue("A report file must be written", reports != null && reports.length == 1);
		assertTrue("The completion message must carry the report path",
			lastMessage.get().contains(reports[0].getName()));

		JsonObject report = JsonParser.parseString(
			new String(Files.readAllBytes(reports[0].toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
		// Pinned parameters — the fields that make two reports comparable.
		assertEquals("ALL_EVERYTHING", report.get("mode").getAsString());
		assertTrue(report.get("routeLimit").getAsInt() > 0);
		assertTrue(report.get("measuredRuns").getAsInt() > 0);

		JsonArray scenarios = report.getAsJsonArray("scenarios");
		assertEquals(1, scenarios.size());
		JsonObject scenario = scenarios.get(0).getAsJsonObject();
		assertEquals("alt-routes", scenario.get("kind").getAsString());
		assertEquals(report.get("measuredRuns").getAsInt(), scenario.getAsJsonArray("runs").size());

		JsonObject median = scenario.getAsJsonObject("median");
		assertTrue("The median run must have completed", median.get("completed").getAsBoolean());
		assertTrue("Median must carry the service timing", median.get("serviceWallMs").getAsLong() >= 0);
		assertTrue("Median must carry the search count", median.get("searches").getAsLong() >= 1);
		JsonArray routes = median.getAsJsonArray("routes");
		assertTrue("The route fingerprint must list found routes", routes.size() >= 1);
		JsonObject firstRoute = routes.get(0).getAsJsonObject();
		assertTrue("Each fingerprinted route carries its cost", firstRoute.get("cost").getAsInt() > 0);
		assertTrue("Each fingerprinted route names its methods",
			firstRoute.getAsJsonArray("methods").size() >= 1);
	}
}
