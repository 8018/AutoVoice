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
    void leavesMultiStopAndMalformedResultsInNormalLoop() {
        AgentToolCall call = new AgentToolCall("1", "resolve_navigation", "{}");
        assertTrue(NavigationCandidateReplies.from(List.of(
                new AgentToolResult(call, "{\"destinations\":[]}", false, false))).isEmpty());
        assertTrue(NavigationCandidateReplies.from(List.of(
                new AgentToolResult(call, "not-json", false, false))).isEmpty());
    }
}
