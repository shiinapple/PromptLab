"""DeepSeek Chat Completions adapter for the PoC."""
from dataclasses import dataclass

BASE_URL = "https://api.deepseek.com"


@dataclass
class Reply:
    status: int | None = None
    body: object = None
    error: str | None = None
    retry_after: str | None = None


class DeepSeek:
    def __init__(self, key, timeout, transport=None):
        import httpx
        import openai

        self.openai = openai
        self.client = openai.OpenAI(
            api_key=key,
            base_url=BASE_URL,
            timeout=timeout,
            max_retries=0,
            http_client=httpx.Client(
                timeout=timeout, transport=transport, trust_env=False, follow_redirects=False,
            ),
        )

    def close(self):
        self.client.close()

    def __call__(self, request):
        params = {name: value for name, value in request.items() if name != "thinking"}
        try:
            response = self.client.chat.completions.with_raw_response.create(
                **params, extra_body={"thinking": request["thinking"]},
            ).http_response
        except self.openai.APITimeoutError:
            return Reply(error="TIMEOUT")
        except self.openai.APIStatusError as exc:
            return Reply(status=exc.status_code, retry_after=exc.response.headers.get("retry-after"))
        except self.openai.APIConnectionError:
            return Reply(error="CONNECTION_ERROR")
        try:
            return Reply(status=response.status_code, body=response.json())
        except (ValueError, UnicodeError):
            return Reply(status=response.status_code, error="INVALID_JSON")
