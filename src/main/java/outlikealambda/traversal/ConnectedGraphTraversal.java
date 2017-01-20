package outlikealambda.traversal;

import org.apache.commons.lang3.tuple.Pair;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import outlikealambda.model.TraversalResult;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static outlikealambda.traversal.TraversalUtils.goStream;

public class ConnectedGraphTraversal {
	private static final Label PERSON_LABEL = Label.label("Person");
	private static final String PERSON_ID = "id";

	@Context
	public GraphDatabaseService gdb;

	@Procedure("friend.author.opinion")
	public Stream<TraversalResult> traverse(
			@Name("userId") long personId,
			@Name("topicId") long topicId
	) {
		Relationships.Topic topic = new Relationships.Topic(topicId);

		Node user = gdb.findNode(PERSON_LABEL, PERSON_ID, personId);

		Iterable<Relationship> userOutgoing = topic.getAllOutgoing(user);

		Map<Node, Relationship> adjacentLinks = goStream(userOutgoing)
				.collect(toMap(
						Relationship::getEndNode,
						Function.identity()
				));

		Map<Node, Node> adjacentAuthors = adjacentLinks.keySet().stream()
				.map(adjacent -> Pair.of(
						adjacent,
						ConnectivityUtils.getDesignatedAuthor(adjacent, topic)
				))
				.filter(p -> p.getRight().isPresent())
				.collect(toMap(
						Pair::getLeft,
						p -> p.getRight().get()
				));

		Map<Node, Node> authorOpinions = adjacentAuthors.values().stream()
				.collect(toMap(
						Function.identity(),
						author -> author.getSingleRelationship(topic.getAuthoredType(), Direction.OUTGOING).getEndNode()
				));

		return TraversalResult.mergeIntoTraversalResults(adjacentLinks, adjacentAuthors, authorOpinions);
	}
}