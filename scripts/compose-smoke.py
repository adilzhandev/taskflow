#!/usr/bin/env python3
"""Check the running Compose app over HTTP using only Python's standard library.

Run after the API is ready. Accounts are disposable; CI removes its own database
volume afterward. Passwords, tokens, and response bodies are never printed.
"""

import json
import secrets
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BASE_URL = "http://127.0.0.1:8080"
checks = 0


class SmokeFailure(Exception):
    pass


def check(condition, message):
    global checks
    if not condition:
        raise SmokeFailure(message)
    checks += 1


def request(method, path, expected=200, body=None, token=None):
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    try:
        response = urlopen(Request(BASE_URL + path, data=data, headers=headers, method=method), timeout=10)
    except HTTPError as exception:
        response = exception
    except URLError:
        raise SmokeFailure("The HTTP API is unavailable") from None
    with response:
        payload = response.read()
        check(response.status == expected,
              f"{method} {path}: expected HTTP {expected}, received {response.status}")
        check(response.headers.get("Set-Cookie") is None, "The API created an unexpected session cookie")
        if expected == 204:
            check(not payload, "A 204 response must have an empty body")
            return None
        check("application/json" in response.headers.get("Content-Type", ""), "Expected a JSON response")
    result = json.loads(payload)
    if expected >= 400:
        check(set(result) == {"status", "message", "path", "fieldErrors"}, "Unexpected error response fields")
        check(result["status"] == expected and result["path"] == path.split("?")[0],
              "Error response status or path does not match the request")
    return result


def main():
    suffix = secrets.token_hex(8)
    password = "Compose-check-" + secrets.token_hex(12)
    credentials = {"email": f"compose-owner-{suffix}@example.com", "password": password}
    registered = request("POST", "/api/auth/register", expected=201, body=credentials)
    check(set(registered["user"]) == {"id", "email", "createdAt"}, "Registration exposed unexpected user fields")
    logged_in = request("POST", "/api/auth/login", body=credentials)
    check(logged_in["user"]["id"] == registered["user"]["id"], "Login returned a different user")
    check(logged_in["tokenType"] == "Bearer", "Login did not return a Bearer token")
    token = logged_in["accessToken"]
    request("GET", "/api/tasks", expected=401)

    task = request("POST", "/api/tasks", expected=201, token=token,
                   body={"title": "Compose smoke task", "description": "Check container persistence"})
    path = f"/api/tasks/{task['id']}"
    check(task["status"] == "TODO" and task["priority"] == "MEDIUM", "Task defaults are incorrect")
    check(request("GET", path, token=token) == task, "The created task was not persisted correctly")

    other_user = request("POST", "/api/auth/register", expected=201,
                         body={"email": f"compose-other-{suffix}@example.com", "password": password})
    other_token = other_user["accessToken"]
    replacement = {"title": "Updated smoke task", "status": "DONE", "priority": "HIGH"}
    request("GET", path, expected=404, token=other_token)
    request("PUT", path, expected=404, token=other_token, body=replacement)
    check(request("GET", path, token=token) == task, "A foreign user changed the task")
    request("DELETE", path, expected=404, token=other_token)
    check(request("GET", path, token=token) == task, "A foreign user deleted or changed the task")
    other_tasks = request("GET", "/api/tasks", token=other_token)
    check(other_tasks["items"] == [] and other_tasks["totalElements"] == 0,
          "Another user's task appeared in the list or total count")

    updated = request("PUT", path, token=token, body=replacement)
    check(all(updated[field] == value for field, value in replacement.items()), "PUT did not replace editable fields")
    check(updated["description"] is None and updated["dueDate"] is None, "PUT did not clear optional fields")
    check(request("GET", path, token=token) == updated, "The updated task was not persisted correctly")
    request("POST", "/api/tasks", expected=400, token=token, body={"title": "   "})
    tasks = request("GET", "/api/tasks?status=DONE&priority=HIGH&page=0&size=1", token=token)
    check(tasks["items"] == [updated] and tasks["totalElements"] == 1 and tasks["totalPages"] == 1,
          "Filtered pagination returned unexpected tasks or counts")
    unmatched = request("GET", "/api/tasks?status=TODO", token=token)
    check(unmatched["items"] == [] and unmatched["totalElements"] == 0, "Status filtering returned a nonmatching task")

    request("DELETE", path, expected=204, token=token)
    request("GET", path, expected=404, token=token)
    remaining = request("GET", "/api/tasks", token=token)
    check(remaining["items"] == [] and remaining["totalElements"] == 0, "Deleted task remains in the list")
    print(f"Compose HTTP smoke passed: {checks} checks.")


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as exception:
        print(f"Compose HTTP smoke failed: {exception}", file=sys.stderr)
        sys.exit(1)
    except Exception as exception:
        # Do not include exception details, which could contain HTTP data.
        print(f"Compose HTTP smoke failed: unexpected {type(exception).__name__}", file=sys.stderr)
        sys.exit(1)
