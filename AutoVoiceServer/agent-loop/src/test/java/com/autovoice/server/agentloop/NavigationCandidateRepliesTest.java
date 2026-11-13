package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavigationCandidateRepliesTest {
    @Test
    void forwardsSearchClarificationAndUnknownChargerAvailability() {
        Reply clarification = NavigationCandidateReplies.fromResolution("""
                {"destinations":[{"query":"充电站","candidates":[]}],"message":"请说要查找的城市。"}
                """).orElseThrow();
        assertEquals("请说要查找的城市。", clarification.text());
        Reply candidates = NavigationCandidateReplies.fromResolution("""
                {"destinations":[{"query":"充电站","candidates":[
                  {"poiname":"充电站","lat":30.1,"lon":120.1}]}],"availabilityNotice":"充电桩空闲状态未知。"}
                """).orElseThrow();
        assertTrue(candidates.speakText().startsWith("充电桩空闲状态未知。"));
    }

    @Test
    void convertsSingleDestinationResolveResultToSelectionAction() {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        String content = """
                {"destinations":[{"query":"万达广场","candidates":[
                  {"poiname":"万达广场东店","lat":30.1,"lon":120.1,"address":"中山路1号"},
                  {"poiname":"万达广场西店","lat":30.2,"lon":120.2,"address":"人民路8号"}]}]}
                """;

        Reply reply = NavigationCandidateReplies.from(
                List.of(new AgentToolResult(call, content, false, false))).orElseThrow();

        assertEquals("choose_destination", reply.intent().intent());
        assertTrue(((String) reply.intent().slots().get("candidates").value()).contains("人民路8号"));
        assertEquals("第1个，万达广场东店，中山路1号；第2个，万达广场西店，人民路8号。请说第几个或具体地址名称。", reply.speakText());
    }

    @Test
    void leavesEmptyAndMalformedResultsInNormalLoop() {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        assertTrue(NavigationCandidateReplies.from(List.of(
                new AgentToolResult(call, "{\"destinations\":[]}", false, false))).isEmpty());
        assertTrue(NavigationCandidateReplies.from(List.of(
                new AgentToolResult(call, "not-json", false, false))).isEmpty());
    }

    @Test
    void convertsMultiStopResultToDraftPreservingEveryCandidate() throws Exception {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        String content = """
                {"destinations":[
                 {"query":"爱情广场","candidates":[
                  {"poiname":"爱情广场","lat":38.8654,"lon":115.4696},
                  {"poiname":"爱情广场停车场","lat":38.866,"lon":115.47}]},
                 {"query":"大旗杆","candidates":[
                  {"poiname":"大旗杆","lat":38.8731,"lon":115.4737}]}]}
                """;

        Reply reply = NavigationCandidateReplies.from(
                List.of(new AgentToolResult(call, content, false, false))).orElseThrow();

        assertEquals("draft_trip", reply.intent().intent());
        assertFalse(reply.intent().slots().containsKey("waypoints"));
        var stops = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                (String) reply.intent().slots().get("stops").value());
        assertEquals(List.of("爱情广场", "大旗杆"),
                List.of(stops.get(0).path("query").asText(), stops.get(1).path("query").asText()));
        assertEquals(2, stops.get(0).path("candidates").size());
    }

    @Test
    void incompleteMultiStopResultDoesNotOpenPartialRoute() {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        String content = """
                {"destinations":[
                 {"query":"第一站","candidates":[{"poiname":"第一站","lat":30.1,"lon":104.1}]},
                 {"query":"第二站","candidates":[]}]}
                """;
        Reply reply = NavigationCandidateReplies.from(
                List.of(new AgentToolResult(call, content, false, false))).orElseThrow();
        assertEquals("draft_trip", reply.intent().intent());
        assertFalse(reply.intent().slots().containsKey("waypoints"));
        assertTrue(((String) reply.intent().slots().get("stops").value()).contains("第二站"));
    }
}
