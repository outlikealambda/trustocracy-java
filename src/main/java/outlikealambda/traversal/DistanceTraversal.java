package outlikealambda.traversal;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.InitialBranchState;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import outlikealambda.model.Connection;
import outlikealambda.model.Journey;
import outlikealambda.model.Person;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class DistanceTraversal {
	private static final Label personLabel = Label.label("Person");
	private static final Label topicLabel = Label.label("Topic");

	// This field declares that we need a GraphDatabaseService
	// as context when any procedure in this class is invoked
	@Context
	public GraphDatabaseService db;

	// This gives us a log instance that outputs messages to the
	// standard log, normally found under `data/log/console.log`
	@Context
	public Log log;

	@Procedure("traverse.distance")
	public Stream<Connection> go(
			@Name("userId") long personId,
			@Name("topicId") long topicId,
			@Name("maxDistance") Double maxDistance
	) {

		log.info("Starting traversal for " + personId + " : " + topicId);

		Iterable<Relationship> userConnections = db.findNode(personLabel, "id", personId)
				.getRelationships(Direction.OUTGOING);

		log.info("Got user connections");

		Map<Node, Relationship> trusteeRelationships = StreamSupport.stream(userConnections.spliterator(), false)
				.filter(RelationshipLabel.isInteresting(topicId))
				.collect(toMap(Relationship::getEndNode, Function.identity()));

		log.info(String.format("Got %d trustees", trusteeRelationships.size()));

		Iterable<Relationship> opinionConnections = db.findNode(topicLabel, "id", topicId)
				.getRelationships(Direction.INCOMING, RelationshipType.withName("ADDRESSES"));

		Map<Node, Node> authorOpinions = StreamSupport.stream(opinionConnections.spliterator(), false)
				.map(Relationship::getStartNode)
				.map(opinion -> opinion.getRelationships(Direction.INCOMING, RelationshipType.withName("OPINES")))
				.flatMap(opinerConnections -> StreamSupport.stream(opinerConnections.spliterator(), false))
				.collect(toMap(Relationship::getStartNode, Relationship::getEndNode));

		log.info(String.format("Got %d authors", authorOpinions.size()));

		PathExpander<Double> expander = new TotalWeightPathExpander(maxDistance, topicId, log);
		Evaluator evaluator = Evaluators.includeWhereEndNodeIs(authorOpinions.keySet().toArray(new Node[authorOpinions.size()]));

		Map<Node, List<Path>> connectedPaths = trusteeRelationships.entrySet().stream()
				.map(trusteeRelationship -> traverse(
						trusteeRelationship.getKey(),
						evaluator,
						expander,
						trusteeRelationship.getValue())
				)
				.flatMap(Traverser::stream)
				.collect(groupingBy(Path::endNode));

		Stream<Connection> unconnected = authorOpinions.entrySet().stream()
				.filter(ao -> !connectedPaths.containsKey(ao.getKey()))
				.map(ao -> buildUnconnected(personFromNode(ao.getKey()), ao.getValue()));

		Stream<Connection> connected = connectedPaths.entrySet().stream()
				.map(buildWritable(authorOpinions, trusteeRelationships));

		return Stream.concat(connected, unconnected);
	}

	private static Function<Map.Entry<Node, List<Path>>, Connection> buildWritable(
			Map<Node, Node> authorOpinions,
			Map<Node, Relationship> trusteeRelationships
	) {
		return authorPath -> buildConnection(
				personFromNode(authorPath.getKey(), trusteeRelationships),
				authorOpinions.get(authorPath.getKey()),
				authorPath.getValue().stream()
						.map(journeyFromPath(trusteeRelationships))
						.collect(toList())
		);
	}

	private static Connection buildUnconnected(Person author, Node opinion) {
		return new Connection(
				// no path!
				null,

				// Opinion
				opinion.getAllProperties(),


				author.toMap(),

				// Qualifications
				Optional.ofNullable(opinion.getSingleRelationship(RelationshipType.withName("QUALIFIES"), Direction.INCOMING))
						.map(Relationship::getStartNode)
						.map(Node::getAllProperties)
						.orElse(null)
		);
	}

	private static Connection buildConnection(Person author, Node opinion, List<Journey> journeys) {
		return new Connection(
			// Paths
			journeys.stream()
					.map(Journey::toMap)
					.collect(toList()),
			// Opinion
			opinion.getAllProperties(),


			author.toMap(),

			// Qualifications
			Optional.ofNullable(opinion.getSingleRelationship(RelationshipType.withName("QUALIFIES"), Direction.INCOMING))
					.map(Relationship::getStartNode)
					.map(Node::getAllProperties)
					.orElse(null)
		);
	}

	private Traverser traverse(Node friend, Evaluator evaluator, PathExpander<Double> expander, final Relationship relationship) {
		Double initialCost = RelationshipLabel.getCost(relationship);

		return db.traversalDescription()
				.order(WeightedRelationshipSelectorFactory.create())
				.expand(expander, new InitialBranchState<Double>() {
					@Override public Double initialState(Path path) {
						return initialCost;
					}

					@Override public InitialBranchState<Double> reverse() {
						return this;
					}
				})
				.evaluator(evaluator)
				.traverse(friend);
	}

	private static Function<Path, Journey> journeyFromPath(Map<Node, Relationship> trusteeRelationships) {
		return p -> new Journey(
				personFromNode(p.startNode(), trusteeRelationships),
				StreamSupport.stream(p.relationships().spliterator(), false)
						.map(Relationship::getType)
						.map(RelationshipType::name)
						.collect(toList())
		);
	}

	private static Person personFromNode(Node n, Map<Node, Relationship> trusteeRelationships) {
		String name = (String) n.getProperty("name");
		long id = (long) n.getProperty("id");

		String relationshipString = Optional.ofNullable(trusteeRelationships.get(n))
				.map(Relationship::getType)
				.map(RelationshipType::name)
				.orElse("NONE");

		return new Person(name, id, relationshipString);
	}

	private static Person personFromNode(Node n) {
		String name = (String) n.getProperty("name");
		long id = (long) n.getProperty("id");

		return new Person(name, id, "NONE");
	}

	private static <T> Predicate<T> not(Predicate<T> pred) {
		return input -> !pred.test(input);
	}
}
