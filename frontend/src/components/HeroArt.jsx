/**
 * The picture on the landing page: a hall seen from above, with a seating
 * plan already laid out and a constraint being satisfied.
 *
 * It is drawn as inline SVG rather than a picture file, so it stays crisp on
 * every screen, needs no network and adds nothing to the build.
 */

const SEATS = {
  // row -> [filled, filled, empty, filled, empty, filled]
  0: [1, 0, 1, 0, 1, 0],
  1: [0, 1, 1, 0, 0, 1],
  2: [1, 1, 0, 0, 1, 1],
}

const COLOURS = ['#4f53a6', '#6f73c4', '#d97706', '#3b3e86', '#7679c4', '#b45309']

export default function HeroArt() {
  const benchW = 54
  const benchH = 24
  const gapX = 10
  const gapY = 22
  const startX = 34
  const startY = 74

  let seatIndex = 0

  return (
    <svg viewBox="0 0 460 380" role="img" aria-label="A seating plan drawn out bench by bench">
      <defs>
        <linearGradient id="hero-room" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#1d2040" />
          <stop offset="100%" stopColor="#12142a" />
        </linearGradient>
        <linearGradient id="hero-bench" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#2f3168" />
          <stop offset="100%" stopColor="#24264d" />
        </linearGradient>
        <linearGradient id="hero-chip" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.98" />
          <stop offset="100%" stopColor="#eef0fb" stopOpacity="0.94" />
        </linearGradient>
        <filter id="hero-shadow" x="-30%" y="-30%" width="160%" height="170%">
          <feDropShadow dx="0" dy="14" stdDeviation="14" floodColor="#05060f" floodOpacity="0.55" />
        </filter>
      </defs>

      {/* the room */}
      <rect x="6" y="6" width="448" height="368" rx="22" fill="url(#hero-room)" />
      <rect
        x="6.75"
        y="6.75"
        width="446.5"
        height="366.5"
        rx="21.5"
        fill="none"
        stroke="#ffffff"
        strokeOpacity="0.1"
        strokeWidth="1.5"
      />

      {/* the front of the hall */}
      <rect x="34" y="30" width="170" height="6" rx="3" fill="#3b3e86" opacity="0.85" />
      <text x="34" y="22" fill="#8f93c9" fontSize="10" fontWeight="600" letterSpacing="1.4">
        FRONT OF HALL
      </text>

      {/* the benches */}
      {Object.keys(SEATS).map((rowKey) => {
        const row = Number(rowKey)
        const y = startY + row * (benchH + gapY)
        return (
          <g key={rowKey}>
            <rect
              x={startX}
              y={y}
              width={benchW * 6 + gapX * 5}
              height={benchH}
              rx="9"
              fill="url(#hero-bench)"
              stroke="#ffffff"
              strokeOpacity="0.08"
            />
            {SEATS[rowKey].map((filled, column) => {
              const cx = startX + 19 + column * (benchW + gapX)
              const cy = y + benchH / 2
              const colour = COLOURS[seatIndex % COLOURS.length]
              const number = filled ? 21 + seatIndex : null
              seatIndex += 1
              return (
                <g key={`${rowKey}-${column}`}>
                  {filled ? (
                    <>
                      <circle cx={cx} cy={cy} r="8.5" fill={colour} />
                      <text
                        x={cx}
                        y={cy + 3.2}
                        fill="#ffffff"
                        fontSize="8"
                        fontWeight="700"
                        textAnchor="middle"
                      >
                        {number}
                      </text>
                    </>
                  ) : (
                    <circle
                      cx={cx}
                      cy={cy}
                      r="8.5"
                      fill="none"
                      stroke="#ffffff"
                      strokeOpacity="0.18"
                      strokeDasharray="2.5 3"
                    />
                  )}
                </g>
              )
            })}
          </g>
        )
      })}

      {/* the desk */}
      <rect x="34" y="272" width="118" height="32" rx="9" fill="#24264d" stroke="#ffffff" strokeOpacity="0.1" />
      <text x="93" y="292" fill="#8f93c9" fontSize="10" fontWeight="600" textAnchor="middle">
        INVIGILATOR
      </text>

      {/* a floating card: the constraint that was satisfied */}
      <g filter="url(#hero-shadow)">
        <rect x="212" y="268" width="214" height="74" rx="14" fill="url(#hero-chip)" />
      </g>
      <circle cx="234" cy="290" r="9" fill="#e8f6ef" />
      <path d="m230 290 3 3 5.5-6" stroke="#1e7a4d" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      <text x="252" y="294" fill="#1c2430" fontSize="11.5" fontWeight="700">
        Constraints met
      </text>
      <text x="228" y="314" fill="#5b6675" fontSize="9.5">
        No two from the same batch side by side
      </text>
      <text x="228" y="329" fill="#8a94a3" fontSize="9.5">
        24 seated · 0 unplaced · 3 halls
      </text>

      {/* a small legend */}
      <g>
        <circle cx="40" cy="352" r="5" fill="#4f53a6" />
        <text x="51" y="355" fill="#8f93c9" fontSize="9.5">
          Batch A
        </text>
        <circle cx="118" cy="352" r="5" fill="#d97706" />
        <text x="129" y="355" fill="#8f93c9" fontSize="9.5">
          Batch B
        </text>
        <circle cx="196" cy="352" r="5" fill="none" stroke="#ffffff" strokeOpacity="0.3" strokeDasharray="2 2" />
        <text x="207" y="355" fill="#8f93c9" fontSize="9.5">
          Free
        </text>
      </g>
    </svg>
  )
}
