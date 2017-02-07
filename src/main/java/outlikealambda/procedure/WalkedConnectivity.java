package outlikealambda.procedure;

import org.apache.commons.lang3.tuple.Pair;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;
import outlikealambda.output.TraversalResult;
import outlikealambda.traversal.ConnectivityManager;
import outlikealambda.traversal.unwind.BasicUnwinder;
import outlikealambda.traversal.walk.Navigator;
import outlikealambda.traversal.walk.Walker;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

public class WalkedConnectivity {
	private static final Label PERSON_LABEL = Label.label("Person");
	private static final String PERSON_ID = "id";

	private static final Label OPINION_LABEL = Label.label("Opinion");
	private static final String OPINION_ID = "id";

	@Context
	public GraphDatabaseService gdb;

	@Procedure("walked.friend.author.opinion")
	public Stream<TraversalResult> traverse(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		Node user = getPerson(userId);
		Navigator navigator = new Navigator(topicId);
		Walker walker = new Walker(navigator);

		Map<Node, Relationship> neighborRelationships = navigator.getRankedAndManualOut(user)
				.collect(toMap(
						Relationship::getEndNode,
						Function.identity()
				));

		Map<Node, Node> neighborToAuthor = neighborRelationships.keySet().stream()
				.filter(navigator::isConnected)
				.map(neighbor -> Pair.of(
						neighbor,
						walker.follow(neighbor)
				))
				.collect(toMap(
						Pair::getLeft,
						Pair::getRight
				));

		Map<Node, Node> authorOpinions = neighborToAuthor.values().stream()
				.distinct()
				.collect(toMap(
						Function.identity(),
						navigator::getOpinion
				));

		Optional<Node> currentTarget = Optional.of(user)
				.filter(navigator::isConnected)
				.map(navigator::getConnectionOut)
				.map(Relationship::getEndNode);

		return TraversalResult.mergeIntoTraversalResults(
				neighborRelationships,
				neighborToAuthor,
				authorOpinions,
				currentTarget
		);
	}

	@Procedure("walked.target.set")
	@PerformsWrites
	public void setTarget(
			@Name("userId") long userId,
			@Name("targetId") long targetId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = getManager(topicId);

		Node user = getPerson(userId);
		Node target = getPerson(targetId);

		manager.setTarget(user, target);
	}

	@Procedure("walked.target.clear")
	@PerformsWrites
	public void clearTarget(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = getManager(topicId);

		Node user = getPerson(userId);

		manager.clearTarget(user);
	}

	@Procedure("walked.opinion.set")
	@PerformsWrites
	public void setOpinion(
			@Name("userId") long userId,
			@Name("opinionId") long opinionId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = getManager(topicId);

		Node user = getPerson(userId);
		Node opinion = getOpinion(opinionId);

		manager.setOpinion(user, opinion);
	}

	@Procedure("walked.opinion.clear")
	@PerformsWrites
	public void clearOpinion(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = getManager(topicId);

		Node user = getPerson(userId);

		manager.clearOpinion(user);
	}

	private Node getPerson(long userId) {
		return gdb.findNode(PERSON_LABEL, PERSON_ID, userId);
	}

	private Node getOpinion(long opinionId) {
		return gdb.findNode(OPINION_LABEL, OPINION_ID, opinionId);
	}

	private static ConnectivityManager getManager(long topicId) {
		Navigator nav = new Navigator(topicId);

		return new ConnectivityManager(
				nav,
				new Walker(nav),
				new BasicUnwinder(nav)
		);
	}
}