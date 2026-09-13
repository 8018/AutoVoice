package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavigationCandidateRepliesTest {
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
        assertEquals("找到2个“万达广场”，请说第几个或具体地址名称", reply.speakText());
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
    void convertsMultiStopResultToRoutePreviewUsingBestCandidatePerStop() throws Exception {
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

        assertEquals("navigate", reply.intent().intent());
        assertEquals("大旗杆", reply.intent().slots().get("poiname").value());
        var waypoints = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                (String) reply.intent().slots().get("waypoints").value());
        assertEquals(1, waypoints.size());
        assertEquals("爱情广场", waypoints.get(0).path("poiname").asText());
        assertEquals("好的，已为您规划先去爱情广场再去大旗杆的导航", reply.speakText());
    }

    @Test
    void incompleteMultiStopResultDoesNotOpenPartialRoute() {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        String content = """
                {"destinations":[
                 {"query":"第一站","candidates":[{"poiname":"第一站","lat":30.1,"lon":104.1}]},
                 {"query":"第二站","candidates":[]}]}
                """;
        assertTrue(NavigationCandidateReplies.from(
                List.of(new AgentToolResult(call, content, false, false))).isEmpty());
    }
}
