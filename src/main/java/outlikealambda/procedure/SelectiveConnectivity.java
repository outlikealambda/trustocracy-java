package outlikealambda.procedure;

import org.apache.commons.lang3.tuple.Pair;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;
import outlikealambda.output.Influence;
import outlikealambda.output.TraversalResult;
import outlikealambda.traversal.ConnectivityAdjuster;
import outlikealambda.traversal.RelationshipFilter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static outlikealambda.utils.Traversals.goStream;

public class SelectiveConnectivity {
	private static final Label PERSON_LABEL = Label.label("Person");
	private static final String PERSON_ID = "id";

	private static final Label OPINION_LABEL = Label.label("Opinion");
	private static final String OPINION_ID = "id";

	@Context
	public GraphDatabaseService gdb;

	@Procedure("selective.friend.author.opinion")
	public Stream<TraversalResult> traverse(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);

		Map<Node, Relationship> adjacentLinks = goStream(user.getRelationships(Direction.OUTGOING, rf.getRankedType()))
				.collect(toMap(
						Relationship::getEndNode,
						Function.identity()
				));

		Optional<Node> topicTarget = rf.getTargetedOutgoing(user)
				.map(outgoingTargeted -> {
					Node t = outgoingTargeted.getEndNode();

					// if target isn't part of the adjacentLinks, add it
					adjacentLinks.putIfAbsent(t, outgoingTargeted);

					return t;
				});

		Map<Node, Node> adjacentAuthors = adjacentLinks.keySet().stream()
				.map(adjacent -> Pair.of(
						adjacent,
						adjuster.getDesignatedAuthor(adjacent)
				))
				.filter(p -> p.getRight().isPresent())
				.collect(toMap(
						Pair::getLeft,
						p -> p.getRight().get()
				));

		Map<Node, Node> authorOpinions = adjacentAuthors.values().stream()
				.distinct()
				.collect(toMap(
						Function.identity(),
						author -> author.getSingleRelationship(rf.getAuthoredType(), Direction.OUTGOING).getEndNode()
				));

		return TraversalResult.mergeIntoTraversalResults(adjacentLinks, adjacentAuthors, authorOpinions, topicTarget);
	}

	@Procedure("selective.target.set")
	@PerformsWrites
	public void setTarget(
			@Name("userId") long userId,
			@Name("targetId") long targetId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);
		Node target = gdb.findNode(PERSON_LABEL, PERSON_ID, targetId);

		adjuster.setTarget(user, target);
	}

	@Procedure("selective.target.clear")
	@PerformsWrites
	public void clearTarget(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);

		adjuster.clearTarget(user);
	}

	@Procedure("selective.influence")
	public Stream<Influence> calculateInfluence(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);

		return Stream.of(adjuster.calculateInfluence(user))
				.map(Influence::new);
	}

	@Procedure("selective.opinion.set")
	@PerformsWrites
	public void setOpinion(
			@Name("userId") long userId,
			@Name("opinionId") long opinionId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);
		Node opinion = gdb.findNode(OPINION_LABEL, OPINION_ID, opinionId);

		adjuster.setOpinion(user, opinion);
	}

	@Procedure("selective.opinion.clear")
	@PerformsWrites
	public void clearOpinion(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		RelationshipFilter rf = new RelationshipFilter(topicId);
		ConnectivityAdjuster adjuster = new ConnectivityAdjuster(rf);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, userId);

		adjuster.clearTarget(user);
	}
}
