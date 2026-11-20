"""Use-case definitions: instructions, greeting and connector tools for each agent.
Edit COMPANY and the prompts to fit your demo."""
import os

COMPANY = os.getenv("COMPANY_NAME", "Acme Clinic")

STYLE = (
    "You are on a live phone call. Speak naturally and briefly: one or two short sentences per turn, "
    "ask one question at a time, never use lists or markdown. If the caller speaks Hindi or Hinglish, "
    "reply in the same language. Read numbers, dates and times back clearly. "
    "When the task is complete, thank the caller, say goodbye and call end_call."
)


def fn(name, description, props, required):
    return {"type": "function", "name": name, "description": description,
            "parameters": {"type": "object", "properties": props, "required": required}}


S = {"type": "string"}

USE_CASES = {
    "receptionist": {
        "greeting": f"Greet the caller warmly as the virtual receptionist of {COMPANY} and ask how you can help.",
        "instructions": (
            f"You are the friendly virtual receptionist for {COMPANY}. {STYLE} "
            "Find out the caller's name and the reason for their call, and whether they want a callback. "
            "Summarize it back in one sentence, then call log_message with the details."
        ),
        "tools": [fn("log_message", "Save the caller's message for the team.",
                     {"name": S, "reason": S, "callback_number": S, "wants_callback": {"type": "boolean"},
                      "summary": S}, ["name", "reason", "summary"])],
    },
    "booking": {
        "greeting": f"Greet the caller on behalf of {COMPANY} and offer to book an appointment.",
        "instructions": (
            f"You book appointments for {COMPANY}. Opening hours are Monday to Saturday, 9 AM to 6 PM. {STYLE} "
            "Collect the caller's full name, preferred date, preferred time, and the purpose of the visit. "
            "If the requested time is outside opening hours, suggest the nearest valid slot. "
            "Read all details back and get a clear yes before calling save_booking. "
            "Use ISO format (YYYY-MM-DD and HH:MM 24h) in the tool call."
        ),
        "tools": [fn("save_booking", "Save a confirmed appointment.",
                     {"name": S, "date": S, "time": S, "purpose": S, "phone": S},
                     ["name", "date", "time"])],
    },
    "lead": {
        "greeting": f"Greet the caller on behalf of {COMPANY}'s sales team and ask what they are looking for.",
        "instructions": (
            f"You qualify inbound sales leads for {COMPANY}. {STYLE} "
            "Ask, one at a time: their name and company, what problem they want to solve, their rough budget, "
            "and their timeline to decide. Do not push or quote prices. "
            "Then score the lead: hot (clear need, budget and timeline under 1 month), warm (need plus one of "
            "budget or timeline), cold (otherwise). Tell the caller a specialist will follow up, then call save_lead."
        ),
        "tools": [fn("save_lead", "Save a qualified lead.",
                     {"name": S, "company": S, "need": S, "budget": S, "timeline": S,
                      "score": {"type": "string", "enum": ["hot", "warm", "cold"]}, "notes": S},
                     ["name", "need", "score"])],
    },
}
