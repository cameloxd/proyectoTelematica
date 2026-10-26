package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlacementTest {
  private final Placement placement = new Placement(0, 8, 1000);

  private static Placement.Node node(String name, long available, int transfers) {
    return new Placement.Node(UUID.randomUUID(), name, 1000, available, transfers, 0);
  }

  @Test
  void tresBloquesQuedanEnTresNodosDistintos() {
    List<Placement.Node> nodes = List.of(node("a", 900, 0), node("b", 900, 0), node("c", 900, 0));
    Map<UUID, Integer> perFile = new HashMap<>();
    Set<UUID> used = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      Placement.Node chosen = placement.choosePrimary(nodes, perFile, 100);
      chosen.assign(100);
      perFile.merge(chosen.id, 1, Integer::sum);
      used.add(chosen.id);
    }
    assertEquals(3, used.size());
  }

  @Test
  void prefiereElNodoConMasEspacio() {
    List<Placement.Node> nodes = List.of(node("lleno", 300, 0), node("libre", 900, 0));
    assertEquals("libre", placement.choosePrimary(nodes, new HashMap<>(), 100).name);
  }

  @Test
  void respetaElColchonYElLimiteDeTransferencias() {
    assertNull(new Placement(250, 8, 1000).choosePrimary(List.of(node("a", 300, 0)), new HashMap<>(), 100));
    assertNull(placement.choosePrimary(List.of(node("ocupado", 900, 8)), new HashMap<>(), 100));
  }

  @Test
  void lasReplicasNuncaRepitenNodo() {
    Placement.Node a = node("a", 900, 0);
    Placement.Node b = node("b", 800, 0);
    Placement.Node c = node("c", 700, 0);
    List<Placement.Node> targets = placement.chooseReplicas(List.of(a, b, c), Set.of(a.id), 100, 2);
    assertEquals(List.of(b, c), targets);
    assertFalse(targets.contains(a));
  }
}
