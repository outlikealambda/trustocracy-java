package outlikealambda.procedure;

import org.apache.commons.lang3.tuple.Pair;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;
import outlikealambda.output.TraversalResult;
import outlikealambda.traversal.ConnectivityManager;
import outlikealambda.traversal.Nodes;
import outlikealambda.traversal.walk.Navigator;
import outlikealambda.utils.Traversals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class DirtyConnectivity {

	@Context
	public GraphDatabaseService gdb;

	@Procedure("dirty.friend.author.opinion")
	public Stream<TraversalResult> traverse(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		Node user = getPerson(userId);
		Navigator navigator = new Navigator(topicId);

		Map<Node, Relationship> neighborRelationships = navigator.getRankedAndManualOut(user)
				.collect(toMap(
						Relationship::getEndNode,
						Function.identity()
				));

		Map<Node, Node> neighborToAuthor = neighborRelationships.keySet().stream()
				.filter(navigator::isConnected)
				.map(neighbor -> Pair.of(
						neighbor,
						Traversals.follow(navigator, neighbor)
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

	@Procedure("dirty.target.set")
	@PerformsWrites
	public void setTarget(
			@Name("userId") long userId,
			@Name("targetId") long targetId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = ConnectivityManager.dirtyWalk(topicId);

		Node user = getPerson(userId);
		Node target = getPerson(targetId);

		manager.setTarget(user, target);
	}

	@Procedure("dirty.target.clear")
	@PerformsWrites
	public void clearTarget(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = ConnectivityManager.dirtyWalk(topicId);

		Node user = getPerson(userId);

		manager.clearTarget(user);
	}

	@Procedure("dirty.opinion.set")
	@PerformsWrites
	public void setOpinion(
			@Name("userId") long userId,
			@Name("opinionId") long opinionId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = ConnectivityManager.dirtyWalk(topicId);

		Node user = getPerson(userId);
		Node opinion = getOpinion(opinionId);

		manager.setOpinion(user, opinion);
	}

	@Procedure("dirty.opinion.clear")
	@PerformsWrites
	public void clearOpinion(
			@Name("userId") long userId,
			@Name("topicId") long topicId
	) {
		ConnectivityManager manager = ConnectivityManager.dirtyWalk(topicId);

		Node user = getPerson(userId);

		manager.clearOpinion(user);
	}

	@Procedure("dirty.ranked.set")
	@PerformsWrites
	public void setRanked(
			@Name("userId") long userId,
			@Name("ranked") List<Long> ranked
	) {
		ConnectivityManager.setRanked(
				getPerson(userId),
				ranked.stream()
						.map(this::getPerson)
						.collect(toList())
		);

		Node user = getPerson(userId);

		getTopics()
				.map(topic -> (Long) topic.getProperty(Nodes.Fields.ID))
				.map(ConnectivityManager::dirtyWalk)
				.forEach(topicManager -> topicManager.updateConnectivity(user));
	}

	private Node getPerson(long userId) {
		return gdb.findNode(Nodes.Labels.PERSON, Nodes.Fields.ID, userId);
	}

	private Node getOpinion(long opinionId) {
		return gdb.findNode(Nodes.Labels.OPINION, Nodes.Fields.ID, opinionId);
	}

	private Stream<Node> getTopics() {
		return gdb.findNodes(Nodes.Labels.TOPIC).stream();
	}
}
