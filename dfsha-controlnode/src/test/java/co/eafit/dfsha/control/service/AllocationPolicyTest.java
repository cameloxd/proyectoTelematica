package co.eafit.dfsha.control.service;

import static org.junit.jupiter.api.Assertions.*;
import co.eafit.dfsha.control.service.AllocationPolicy.Candidate;
import java.util.*;
import org.junit.jupiter.api.Test;

class AllocationPolicyTest {
  @Test void distributesFirstRoundAcrossDifferentNodes() {
    AllocationPolicy policy=new AllocationPolicy();Map<UUID,Integer> assigned=new HashMap<>();
    List<Candidate> nodes=new ArrayList<>();
    for(int i=0;i<3;i++)nodes.add(new Candidate(UUID.randomUUID(),"node-"+i,1000,900,0,0,0));
    Set<UUID> selected=new HashSet<>();
    for(int i=0;i<3;i++){
      Candidate node=policy.select(nodes,assigned,100,0,8,1000).orElseThrow();
      selected.add(node.id());assigned.merge(node.id(),1,Integer::sum);
    }
    assertEquals(3,selected.size());
  }

  @Test void prefersSpaceWhenNodesAreInSameRound() {
    AllocationPolicy policy=new AllocationPolicy();UUID roomy=UUID.randomUUID();
    List<Candidate> nodes=List.of(new Candidate(roomy,"roomy",1000,900,0,0,0),
        new Candidate(UUID.randomUUID(),"full",1000,300,0,0,0));
    assertEquals(roomy,policy.select(nodes,new HashMap<>(),100,0,8,1000).orElseThrow().id());
  }

  @Test void rejectsNodesThatWouldConsumeTheReservedFloor() {
    Candidate node=new Candidate(UUID.randomUUID(),"node",1000,300,100,0,0);
    assertTrue(new AllocationPolicy().select(List.of(node),new HashMap<>(),101,100,8,1000).isEmpty());
  }

  @Test void rejectsNodesAtTheTransferLimit() {
    Candidate node=new Candidate(UUID.randomUUID(),"node",1000,900,0,8,0);
    assertTrue(new AllocationPolicy().select(List.of(node),new HashMap<>(),100,0,8,1000).isEmpty());
  }
}
