package io.github.mgeladzerezo.miniorm.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Kahn's algorithm with a tie-break, used to order INSERTs (and, reversed, DELETEs) so that a
 * row is written after every row it references.
 *
 * <p>The tie-break matters as much as the ordering: among the nodes that are ready, the
 * smallest by {@code priority} goes first. The flush passes "entity type rank, then order of
 * persist" as priority, which clusters rows of the same table next to each other so they can
 * be sent as one JDBC batch, while still honouring instance-level dependencies such as a
 * child category that must follow its parent category.
 */
public final class TopologicalSort {

    private TopologicalSort() {
    }

    /** The nodes that could not be ordered because they depend on each other. */
    public static final class CycleException extends RuntimeException {
        private final transient List<?> remaining;

        CycleException(List<?> remaining) {
            super("Dependency cycle among " + remaining.size() + " nodes", null, false, false);
            this.remaining = remaining;
        }

        public List<?> remaining() {
            return remaining;
        }
    }

    /**
     * Orders {@code nodes} so that every node comes after its dependencies.
     *
     * @param nodes        the nodes to order; compared by identity
     * @param dependencies for a node, the nodes that must precede it; nodes outside {@code nodes}
     *                     and self-references are ignored
     * @param priority     order among nodes whose dependencies are satisfied
     * @throws CycleException if the dependencies are cyclic
     */
    public static <N> List<N> sort(List<N> nodes, Function<N, ? extends Collection<? extends N>> dependencies,
                                   Comparator<? super N> priority) {
        Map<N, Integer> missing = new IdentityHashMap<>();
        Map<N, List<N>> dependents = new IdentityHashMap<>();
        for (N node : nodes) {
            missing.put(node, 0);
        }
        for (N node : nodes) {
            for (N dependency : dependencies.apply(node)) {
                if (dependency != node && missing.containsKey(dependency)) {
                    missing.merge(node, 1, Integer::sum);
                    dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(node);
                }
            }
        }
        PriorityQueue<N> ready = new PriorityQueue<>(Math.max(1, nodes.size()), priority);
        for (N node : nodes) {
            if (missing.get(node) == 0) {
                ready.add(node);
            }
        }
        List<N> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            N node = ready.poll();
            ordered.add(node);
            for (N dependent : dependents.getOrDefault(node, List.of())) {
                if (missing.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() < nodes.size()) {
            throw new CycleException(nodes.stream().filter(n -> missing.get(n) > 0).toList());
        }
        return ordered;
    }

    /**
     * Assigns each node a rank such that dependencies have lower ranks. Nodes on a cycle
     * cannot be ranked relative to each other; they get the ranks after all orderable nodes,
     * in input order, and instance-level sorting sorts out the rest.
     */
    public static <N> Map<N, Integer> ranks(List<N> nodes, Function<N, ? extends Collection<? extends N>> dependencies) {
        Map<N, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            position.put(nodes.get(i), i);
        }
        Comparator<N> inputOrder = Comparator.comparing(position::get);
        List<N> ordered;
        try {
            ordered = sort(nodes, dependencies, inputOrder);
        } catch (CycleException e) {
            // Rank what can be ranked by repeatedly removing the cyclic remainder.
            List<N> cyclic = new ArrayList<>();
            for (Object node : e.remaining()) {
                @SuppressWarnings("unchecked")
                N typed = (N) node;
                cyclic.add(typed);
            }
            List<N> acyclic = nodes.stream().filter(n -> !containsIdentity(cyclic, n)).toList();
            ordered = new ArrayList<>(sort(acyclic, dependencies, inputOrder));
            ordered.addAll(cyclic);
        }
        Map<N, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            ranks.put(ordered.get(i), i);
        }
        return ranks;
    }

    private static boolean containsIdentity(List<?> list, Object element) {
        for (Object candidate : list) {
            if (candidate == element) {
                return true;
            }
        }
        return false;
    }
}
