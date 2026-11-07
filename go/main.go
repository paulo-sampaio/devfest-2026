package main

import (
	_ "time/tzdata"
	"html/template"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

var bootTime = time.Now()
var coldStartDuration = time.Since(bootTime)

type CityInfo struct {
	ID             string
	Name           string
	Country        string
	Flag           string
	Timezone       string
	HistoricalFact string
	CurrentTime    string
	CurrentDate    string
	Offset         string
	IsActive       bool
}

type PageData struct {
	Title       string
	AccentColor string
	Cities      []CityInfo
	ActiveCity  CityInfo
	RuntimeInfo string
	ColdStartMs int64
	Hostname    string
}

var catalog = []CityInfo{
	{
		ID:             "salvador",
		Name:           "Salvador",
		Country:        "Brasil",
		Flag:           "🇧🇷",
		Timezone:       "America/Bahia",
		HistoricalFact: "Primeira capital do Brasil (1549) e primeira cidade planejada das Américas no período colonial português.",
	},
	{
		ID:             "kyiv",
		Name:           "Kyiv",
		Country:        "Ucrânia",
		Flag:           "🇺🇦",
		Timezone:       "Europe/Kyiv",
		HistoricalFact: "Capital do Rus de Kyiv há mais de 1.500 anos, com a Catedral de Santa Sofia (século XI) mantendo mosaicos originais intactos.",
	},
	{
		ID:             "varanasi",
		Name:           "Varanasi",
		Country:        "Índia",
		Flag:           "🇮🇳",
		Timezone:       "Asia/Kolkata",
		HistoricalFact: "Uma das cidades continuamente habitadas mais antigas do mundo (mais de 3.000 anos), sagrada às margens do Rio Ganges.",
	},
	{
		ID:             "xian",
		Name:           "Xi'an",
		Country:        "China",
		Flag:           "🇨🇳",
		Timezone:       "Asia/Shanghai",
		HistoricalFact: "Ponto de partida oriental da histórica Rota da Seda e lar do lendário Exército de Terracota com mais de 8.000 guerreiros enterrados.",
	},
	{
		ID:             "cape-town",
		Name:           "Cidade do Cabo",
		Country:        "África do Sul",
		Flag:           "🇿🇦",
		Timezone:       "Africa/Johannesburg",
		HistoricalFact: "Fundada em 1652 no sopé da Table Mountain, abriga a Ilha Robben onde Nelson Mandela permaneceu preso por 18 anos.",
	},
	{
		ID:             "alexandria",
		Name:           "Alexandria",
		Country:        "Egito",
		Flag:           "🇪🇬",
		Timezone:       "Africa/Cairo",
		HistoricalFact: "Fundada por Alexandre o Grande em 331 a.C., foi sede do lendário Farol de Alexandria e da mais famosa biblioteca da antiguidade.",
	},
}

func getHostname() string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "container-pod"
}

func main() {
	coldStartDuration = time.Since(bootTime)

	tmpl, err := template.ParseFiles("template.html")
	if err != nil {
		log.Fatalf("Falha ao carregar template.html: %v", err)
	}

	http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}

		selectedID := strings.TrimSpace(r.URL.Query().Get("tz"))
		if selectedID == "" {
			selectedID = "salvador"
		}

		now := time.Now().UTC()
		var cities []CityInfo
		var activeCity CityInfo
		found := false

		for _, item := range catalog {
			loc, err := time.LoadLocation(item.Timezone)
			if err != nil {
				loc = time.UTC
			}
			localTime := now.In(loc)
			nameZone, offsetSec := localTime.Zone()
			offsetHours := offsetSec / 3600

			c := item
			c.CurrentTime = localTime.Format("15:04:05")
			c.CurrentDate = localTime.Format("02/01/2006 (Monday)")
			c.Offset = fmtOffset(nameZone, offsetHours)
			c.IsActive = (item.ID == selectedID)

			if c.IsActive {
				activeCity = c
				found = true
			}
			cities = append(cities, c)
		}

		if !found && len(cities) > 0 {
			cities[0].IsActive = true
			activeCity = cities[0]
		}

		data := PageData{
			Title:       "Go TZ Explorer",
			AccentColor: "#00ADD8",
			Cities:      cities,
			ActiveCity:  activeCity,
			RuntimeInfo: "Go 1.27 (Scratch / timetzdata)",
			ColdStartMs: coldStartDuration.Milliseconds(),
			Hostname:    getHostname(),
		}

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = tmpl.Execute(w, data)
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("Go Microservice pronto em :%s (cold start: %v)", port, coldStartDuration)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatalf("Erro no servidor HTTP: %v", err)
	}
}

func fmtOffset(zone string, hours int) string {
	if hours == 0 {
		return "UTC"
	}
	if hours > 0 {
		return zone + " UTC+" + string(rune('0'+hours))
	}
	return zone + " UTC-" + string(rune('0'+(-hours)))
}
