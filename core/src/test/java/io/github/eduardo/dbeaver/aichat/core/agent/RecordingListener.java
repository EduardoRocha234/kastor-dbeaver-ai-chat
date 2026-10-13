package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import java.util.ArrayList;
import java.util.List;

class RecordingListener implements SessionListener {

    final List<String> events = new ArrayList<>();

    @Override
    public void onAssistantStart() {
        events.add("start");
    }

    @Override
    public void onTextDelta(String text) {
        events.add("text:" + text);
    }

    @Override
    public void onAssistantEnd(AssistantTurn turn) {
        events.add("end:" + turn.stopReason());
    }

    @Override
    public void onToolStart(ToolCall call) {
        events.add("tool:" + call.name());
    }

    @Override
    public void onToolEnd(ToolCall call, ToolOutcome outcome) {
        events.add("toolEnd:" + call.name() + (outcome.error() ? ":error" : ""));
    }

    @Override
    public void onNotice(String message) {
        events.add("notice:" + message);
    }

    @Override
    public void onError(String message) {
        events.add("error:" + message);
    }

    @Override
    public void onTurnFinished() {
        events.add("finished");
    }
}
