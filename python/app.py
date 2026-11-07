import os
import socket
import time
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from starlette.applications import Starlette
from starlette.responses import HTMLResponse, PlainTextResponse
from starlette.routing import Route

boot_start = time.perf_counter()
cold_start_ms = 0

CITIES = [
    {
        "id": "salvador",
        "name": "Salvador",
        "country": "Brasil",
        "flag": "🇧🇷",
        "timezone": "America/Bahia",
        "fact": "Primeira capital do Brasil (1549) e primeira cidade planejada das Américas no período colonial português.",
    },
    {
        "id": "kyiv",
        "name": "Kyiv",
        "country": "Ucrânia",
        "flag": "🇺🇦",
        "timezone": "Europe/Kyiv",
        "fact": "Capital do Rus de Kyiv há mais de 1.500 anos, com a Catedral de Santa Sofia (século XI) mantendo mosaicos originais intactos.",
    },
    {
        "id": "varanasi",
        "name": "Varanasi",
        "country": "Índia",
        "flag": "🇮🇳",
        "timezone": "Asia/Kolkata",
        "fact": "Uma das cidades continuamente habitadas mais antigas do mundo (mais de 3.000 anos), sagrada às margens do Rio Ganges.",
    },
    {
        "id": "xian",
        "name": "Xi'an",
        "country": "China",
        "flag": "🇨🇳",
        "timezone": "Asia/Shanghai",
        "fact": "Ponto de partida oriental da histórica Rota da Seda e lar do lendário Exército de Terracota com mais de 8.000 guerreiros enterrados.",
    },
    {
        "id": "cape-town",
        "name": "Cidade do Cabo",
        "country": "África do Sul",
        "flag": "🇿🇦",
        "timezone": "Africa/Johannesburg",
        "fact": "Fundada em 1652 no sopé da Table Mountain, abriga a Ilha Robben onde Nelson Mandela permaneceu preso por 18 anos.",
    },
    {
        "id": "alexandria",
        "name": "Alexandria",
        "country": "Egito",
        "flag": "🇪🇬",
        "timezone": "Africa/Cairo",
        "fact": "Fundada por Alexandre o Grande em 331 a.C., foi sede do lendário Farol de Alexandria e da mais famosa biblioteca da antiguidade.",
    },
]

def render_html(selected_id: str) -> str:
    now_utc = datetime.now(timezone.utc)
    active_city = None
    city_buttons = []

    for item in CITIES:
        try:
            tz = ZoneInfo(item["timezone"])
        except Exception:
            tz = timezone.utc

        local_dt = now_utc.astimezone(tz)
        is_active = (item["id"] == selected_id)
        
        city_data = {
            **item,
            "current_time": local_dt.strftime("%H:%M:%S"),
            "current_date": local_dt.strftime("%d/%m/%Y (%A)"),
            "offset": local_dt.strftime("%Z UTC%z"),
            "is_active": is_active,
        }

        if is_active:
            active_city = city_data

        active_cls = "active" if is_active else ""
        city_buttons.append(
            f'<a href="/?tz={item["id"]}" class="city-btn {active_cls}">'
            f'{item["flag"]} {item["name"]}</a>'
        )

    if not active_city and CITIES:
        active_city = {
            **CITIES[0],
            "current_time": now_utc.strftime("%H:%M:%S"),
            "current_date": now_utc.strftime("%d/%m/%Y"),
            "offset": "UTC",
        }

    template_path = os.path.join(os.path.dirname(__file__), "template.html")
    with open(template_path, "r", encoding="utf-8") as f:
        html = f.read()

    # Interpolação direta e leve sem frameworks pesados de template
    replacements = {
        "{{.Title}}": "Python TZ Explorer",
        "{{.AccentColor}}": "#3776AB",
        "{{.ActiveCity.Flag}}": active_city["flag"],
        "{{.ActiveCity.Name}}": active_city["name"],
        "{{.ActiveCity.Country}}": active_city["country"],
        "{{.ActiveCity.Timezone}}": active_city["timezone"],
        "{{.ActiveCity.CurrentTime}}": active_city["current_time"],
        "{{.ActiveCity.CurrentDate}}": active_city["current_date"],
        "{{.ActiveCity.Offset}}": active_city["offset"],
        "{{.ActiveCity.HistoricalFact}}": active_city["fact"],
        "{{.RuntimeInfo}}": "Python 3.15 (uv + Starlette)",
        "{{.ColdStartMs}}": str(cold_start_ms),
        "{{.Hostname}}": socket.gethostname(),
    }

    # Substitui a seção inteira do bloco <nav class="city-nav">...</nav>
    start_nav = '<nav class="city-nav">'
    end_nav = '</nav>'
    if start_nav in html and end_nav in html:
        idx_start = html.find(start_nav) + len(start_nav)
        idx_end = html.find(end_nav)
        html = html[:idx_start] + "\n      " + "\n      ".join(city_buttons) + "\n    " + html[idx_end:]

    for k, v in replacements.items():
        html = html.replace(k, v)

    return html

async def homepage(request):
    selected = request.query_params.get("tz", "salvador").strip().lower()
    return HTMLResponse(render_html(selected))

async def healthz(request):
    return PlainTextResponse("OK")

async def lifespan(app):
    global cold_start_ms
    cold_start_ms = round((time.perf_counter() - boot_start) * 1000)
    print(f"Python TZ Explorer pronto em {cold_start_ms}ms")
    yield

app = Starlette(
    routes=[
        Route("/", homepage, methods=["GET"]),
        Route("/healthz", healthz, methods=["GET"]),
    ],
    lifespan=lifespan,
)
