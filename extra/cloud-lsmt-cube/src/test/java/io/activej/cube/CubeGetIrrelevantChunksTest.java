package io.activej.cube;

import io.activej.aggregation.*;
import io.activej.aggregation.ot.AggregationDiff;
import io.activej.codegen.DefiningClassLoader;
import io.activej.csp.process.frames.FrameFormat;
import io.activej.csp.process.frames.LZ4FrameFormat;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffCodec;
import io.activej.cube.ot.CubeOT;
import io.activej.etl.LogDiff;
import io.activej.etl.LogDiffCodec;
import io.activej.etl.LogOT;
import io.activej.etl.LogOTState;
import io.activej.eventloop.Eventloop;
import io.activej.fs.LocalActiveFs;
import io.activej.ot.OTCommit;
import io.activej.ot.OTStateManager;
import io.activej.ot.repository.OTRepositoryMySql;
import io.activej.ot.system.OTSystem;
import io.activej.ot.uplink.OTUplinkImpl;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.AggregationPredicates.gt;
import static io.activej.aggregation.PrimaryKey.ofArray;
import static io.activej.aggregation.fieldtype.FieldTypes.*;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.common.collection.CollectionUtils.map;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.cube.TestUtils.initializeRepository;
import static io.activej.promise.TestUtils.await;
import static io.activej.test.TestUtils.dataSource;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;

public final class CubeGetIrrelevantChunksTest {
	private static final DefiningClassLoader CLASS_LOADER = DefiningClassLoader.create();
	private static final OTSystem<LogDiff<CubeDiff>> OT_SYSTEM = LogOT.createLogOT(CubeOT.createCubeOT());

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int NUMBER_MIN = 0;
	private static final int NUMBER_MAX = 100;

	private static final int DATE_MIN_DAYS = (int) LocalDate.of(2020, Month.JANUARY, 1).toEpochDay();
	private static final int DATE_MAX_DAYS = (int) LocalDate.of(2021, Month.JANUARY, 1).toEpochDay();

	private static final LocalDate LOWER_DATE_BOUNDARY = LocalDate.of(2020, Month.JULY, 31);
	private static final int LOWER_DATE_BOUNDARY_DAYS = (int) LOWER_DATE_BOUNDARY.toEpochDay();
	private static final AggregationPredicate DATE_PREDICATE = gt("date", LOWER_DATE_BOUNDARY);
	private static final int LOWER_NUMBER_BOUNDARY = 50;
	private static final AggregationPredicate ADVERTISER_PREDICATE = gt("advertiser", LOWER_NUMBER_BOUNDARY);

	private Eventloop eventloop;
	private OTStateManager<Long, LogDiff<CubeDiff>> stateManager;
	private ActiveFsChunkStorage<Long> chunkStorage;
	private Executor executor;
	private Cube.AggregationConfig dateAggregation;
	private Cube.AggregationConfig advertiserDateAggregation;
	private OTUplinkImpl<Long, LogDiff<CubeDiff>, OTCommit<Long, LogDiff<CubeDiff>>> node;
	private Cube basicCube;
	private Cube cube;

	private long chunkId;

	private final Set<Object> toBePreserved = new HashSet<>();
	private final Set<Object> toBeCleanedUp = new HashSet<>();

	@Before
	public void before() throws Exception {
		chunkId = 0;
		toBeCleanedUp.clear();
		toBePreserved.clear();

		Path aggregationsDir = temporaryFolder.newFolder().toPath();

		eventloop = Eventloop.getCurrentEventloop();
		executor = Executors.newCachedThreadPool();

		LocalActiveFs fs = LocalActiveFs.create(eventloop, executor, aggregationsDir)
				.withTempDir(Files.createTempDirectory(""));
		await(fs.start());
		FrameFormat frameFormat = LZ4FrameFormat.create();
		chunkStorage = ActiveFsChunkStorage.create(eventloop, ChunkIdCodec.ofLong(), new IdGeneratorStub(), frameFormat, fs);

		dateAggregation = id("date")
				.withDimensions("date")
				.withMeasures("impressions", "clicks", "conversions", "revenue");

		advertiserDateAggregation = id("advertiser-date")
				.withDimensions("advertiser", "date")
				.withMeasures("impressions", "clicks", "conversions", "revenue");

		basicCube = createBasicCube()
				.withAggregation(dateAggregation)
				.withAggregation(advertiserDateAggregation);

		DataSource dataSource = dataSource("test.properties");
		OTRepositoryMySql<LogDiff<CubeDiff>> repository = OTRepositoryMySql.create(eventloop, executor, dataSource, new IdGeneratorStub(),
				OT_SYSTEM, LogDiffCodec.create(CubeDiffCodec.create(basicCube)));
		initializeRepository(repository);

		LogOTState<CubeDiff> cubeDiffLogOTState = LogOTState.create(basicCube);
		node = OTUplinkImpl.create(repository, OT_SYSTEM);
		stateManager = OTStateManager.create(eventloop, OT_SYSTEM, node, cubeDiffLogOTState);
		await(stateManager.checkout());
	}

	@Test
	public void date() {
		cube = createBasicCube().withAggregation(dateAggregation.withPredicate(DATE_PREDICATE));

		toBePreserved.add(addChunk("date", ofArray(DATE_MIN_DAYS), ofArray(DATE_MAX_DAYS)));
		toBePreserved.add(addChunk("date", ofArray(DATE_MIN_DAYS + 50), ofArray(DATE_MAX_DAYS - 50)));

		toBeCleanedUp.add(addChunk("date", ofArray(DATE_MIN_DAYS), ofArray(LOWER_DATE_BOUNDARY_DAYS)));
		toBeCleanedUp.add(addChunk("date", ofArray(DATE_MIN_DAYS), ofArray(DATE_MIN_DAYS + 50)));

		doTest();
	}

	@Test
	public void advertiserDate_DatePredicate() {
		cube = createBasicCube().withAggregation(advertiserDateAggregation.withPredicate(DATE_PREDICATE));

		toBePreserved.add(addChunk("advertiser-date", ofArray(5, DATE_MIN_DAYS), ofArray(6, DATE_MAX_DAYS)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(1, DATE_MIN_DAYS + 50), ofArray(20, DATE_MAX_DAYS - 50)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(6, DATE_MIN_DAYS), ofArray(7, DATE_MIN_DAYS + 1)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(10, DATE_MIN_DAYS), ofArray(10, LOWER_DATE_BOUNDARY_DAYS + 1)));

		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(5, DATE_MIN_DAYS), ofArray(5, DATE_MIN_DAYS + 50)));
		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(10, DATE_MIN_DAYS + 50), ofArray(10, LOWER_DATE_BOUNDARY_DAYS)));

		doTest();
	}

	@Test
	public void advertiserDate_AdvertiserPredicate() {
		cube = createBasicCube().withAggregation(advertiserDateAggregation.withPredicate(ADVERTISER_PREDICATE));

		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MIN, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY + 1, DATE_MAX_DAYS)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MIN + 50, DATE_MIN_DAYS + 50), ofArray(NUMBER_MAX - 10, DATE_MAX_DAYS - 50)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(LOWER_NUMBER_BOUNDARY - 1, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY + 1, DATE_MIN_DAYS + 1)));

		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(NUMBER_MIN, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY, DATE_MIN_DAYS + 50)));
		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(LOWER_NUMBER_BOUNDARY - 10, DATE_MIN_DAYS + 50), ofArray(LOWER_NUMBER_BOUNDARY - 5, LOWER_DATE_BOUNDARY_DAYS)));

		doTest();
	}

	@Test
	public void advertiserDate_AdvertiserPredicateAndDatePredicate() {
		cube = createBasicCube().withAggregation(advertiserDateAggregation
				.withPredicate(AggregationPredicates.and(ADVERTISER_PREDICATE, DATE_PREDICATE)));

		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MIN, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY + 1, DATE_MAX_DAYS)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MIN + 50, DATE_MIN_DAYS + 50), ofArray(NUMBER_MAX - 10, DATE_MAX_DAYS - 50)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(LOWER_NUMBER_BOUNDARY - 1, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY + 1, DATE_MIN_DAYS + 1)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MAX - 10, DATE_MAX_DAYS - 50), ofArray(NUMBER_MAX - 5, DATE_MAX_DAYS - 25)));
		toBePreserved.add(addChunk("advertiser-date", ofArray(NUMBER_MAX - 10, DATE_MAX_DAYS - 50), ofArray(NUMBER_MAX - 10, DATE_MAX_DAYS - 25)));

		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(NUMBER_MIN, DATE_MIN_DAYS), ofArray(LOWER_NUMBER_BOUNDARY, DATE_MAX_DAYS - 50)));
		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(LOWER_NUMBER_BOUNDARY - 10, DATE_MIN_DAYS + 50), ofArray(LOWER_NUMBER_BOUNDARY - 5, LOWER_DATE_BOUNDARY_DAYS + 10)));
		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(LOWER_NUMBER_BOUNDARY - 10, DATE_MIN_DAYS + 50), ofArray(LOWER_NUMBER_BOUNDARY - 10, LOWER_DATE_BOUNDARY_DAYS + 10)));
		toBeCleanedUp.add(addChunk("advertiser-date", ofArray(NUMBER_MIN + 10, DATE_MAX_DAYS - 50), ofArray(NUMBER_MIN + 10, DATE_MAX_DAYS - 25)));

		doTest();
	}

	private void doTest() {
		await(stateManager.sync());

		Set<Object> expectedChunks = new HashSet<>();
		expectedChunks.addAll(toBePreserved);
		expectedChunks.addAll(toBeCleanedUp);

		assertEquals(expectedChunks, basicCube.getAllChunks());

		stateManager = OTStateManager.create(eventloop, OT_SYSTEM, node, LogOTState.create(cube));
		await(stateManager.checkout());

		Set<Object> irrelevantChunks = cube.getIrrelevantChunks()
				.values()
				.stream()
				.flatMap(Collection::stream)
				.map(AggregationChunk::getChunkId)
				.collect(toSet());
		assertEquals(toBeCleanedUp, irrelevantChunks);
	}

	private long addChunk(String aggregationId, PrimaryKey minKey, PrimaryKey maxKey) {
		long chunkId = ++this.chunkId;
		stateManager.add(LogDiff.forCurrentPosition(CubeDiff.of(map(
				aggregationId,
				AggregationDiff.of(Collections.singleton(AggregationChunk.create(
						chunkId,
						cube.getAggregation(aggregationId).getMeasures(),
						minKey,
						maxKey,
						1
				))))
		)));
		return chunkId;
	}

	private Cube createBasicCube() {
		return Cube.create(eventloop, executor, CLASS_LOADER, chunkStorage)
				.withDimension("date", ofLocalDate())
				.withDimension("advertiser", ofInt())
				.withDimension("campaign", ofInt())
				.withDimension("banner", ofInt())
				.withRelation("campaign", "advertiser")
				.withRelation("banner", "campaign")
				.withMeasure("impressions", sum(ofLong()))
				.withMeasure("clicks", sum(ofLong()))
				.withMeasure("conversions", sum(ofLong()))
				.withMeasure("revenue", sum(ofDouble()));
	}
}
