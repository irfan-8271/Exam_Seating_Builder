import { useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import Brand from '../components/Brand'
import HeroArt from '../components/HeroArt'
import { planApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { formatMoney } from '../components/ui'
import {
  IconArrowRight,
  IconCheck,
  IconClose,
  IconFile,
  IconGrid,
  IconMenu,
  IconTable,
  IconUpload,
  IconUsers,
  IconWand,
} from '../components/Icons'

/** What the product does, in six lines. */
const FEATURES = [
  {
    icon: <IconUpload />,
    title: 'Any roster, as it is',
    body:
      'Upload the sheet your office already has. The columns may be named anything — the system reads the headings, works out which of them could identify a student and asks you to confirm.',
  },
  {
    icon: <IconUsers />,
    title: 'Identification you decide',
    body:
      'A roll number, an application number, a registration number — or two attributes together. No roll number at all is fine. Your choice is remembered for next time.',
  },
  {
    icon: <IconWand />,
    title: 'A plan under your rules',
    body:
      'Set how strictly students must be separated, which attributes count as a group, how far apart two from the same group must sit. The engine solves it or tells you why it could not.',
  },
  {
    icon: <IconGrid />,
    title: 'Move anyone by hand',
    body:
      'Generated plans are not the last word. Pick a student, pick a seat, and the move is checked against the same rules before it is accepted.',
  },
  {
    icon: <IconFile />,
    title: 'Ready for the exam day',
    body:
      'The seating workbook, the door notices and the invigilator sheets come out of the same plan, so the paper in the hall matches the screen.',
  },
  {
    icon: <IconTable />,
    title: 'One workspace, several people',
    body:
      'Give the exam cell and the invigilators their own accounts, with their own permissions, inside the same institution.',
  },
]

const STEPS = [
  {
    title: 'Bring your roster',
    body:
      'Add students one at a time, or upload the spreadsheet your office already keeps. Either way the result is the same list.',
  },
  {
    title: 'Say how students are told apart',
    body:
      'The upload is analysed and the columns that could identify a student are offered. Pick one, or a pair, and it becomes your standing rule.',
  },
  {
    title: 'Describe the halls and generate',
    body:
      'Say how the benches are arranged and how firmly the groups must be kept apart. The plan is laid out, checked and ready to move.',
  },
]

/** Reads the plan catalogue so the prices are the real ones. */
function usePlans() {
  const [plans, setPlans] = useState([])
  useEffect(() => {
    let cancelled = false
    planApi
      .list()
      .then((list) => {
        if (!cancelled) setPlans(list)
      })
      .catch(() => {
        if (!cancelled) setPlans([])
      })
    return () => {
      cancelled = true
    }
  }, [])
  return plans
}

function money(plan) {
  if (!plan) return 'Free'
  return formatMoney(plan.monthlyPrice, {
    symbol: plan.currencySymbol || '₹',
    code: plan.currencyCode || 'INR',
    freeLabel: 'Free',
  })
}

export default function LandingPage() {
  const { user, isPlatformAdmin } = useAuth()
  const plans = usePlans()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  useEffect(() => {
    function handleKeyOrResize(event) {
      if (event.type === 'keydown' && event.key === 'Escape') {
        setMobileMenuOpen(false)
      } else if (event.type === 'resize' && window.innerWidth > 840) {
        setMobileMenuOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyOrResize)
    window.addEventListener('resize', handleKeyOrResize)
    return () => {
      window.removeEventListener('keydown', handleKeyOrResize)
      window.removeEventListener('resize', handleKeyOrResize)
    }
  }, [])

  if (user) {
    return <Navigate to={isPlatformAdmin ? '/admin' : '/dashboard'} replace />
  }

  const featuredCode = plans.some((p) => p.code === 'PRO')
    ? 'PRO'
    : plans[1]?.code || null

  return (
    <div className="site">
      <header className="site-header">
        <div className="site-header__inner">
          <Brand />
          <nav className="site-nav" aria-label="Primary navigation">
            <a href="#features">What it does</a>
            <a href="#how">How it works</a>
            <a href="#plans">Plans</a>
          </nav>
          <div className="site-header__actions">
            <Link className="btn btn--ghost site-header__signin" to="/signin">
              Sign in
            </Link>
            <Link className="btn btn--primary site-header__cta" to="/register">
              Create an account
            </Link>
            <button
              type="button"
              className="icon-button site-menu-toggle"
              aria-expanded={mobileMenuOpen}
              aria-controls="site-mobile-menu"
              aria-label={mobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}
              onClick={() => setMobileMenuOpen((open) => !open)}
            >
              {mobileMenuOpen ? (
                <IconClose width={20} height={20} />
              ) : (
                <IconMenu width={20} height={20} />
              )}
            </button>
          </div>
        </div>

        {mobileMenuOpen && (
          <nav
            id="site-mobile-menu"
            className="site-mobile-menu"
            aria-label="Mobile navigation"
          >
            <div className="site-mobile-menu__links">
              <a href="#features" onClick={() => setMobileMenuOpen(false)}>
                What it does
              </a>
              <a href="#how" onClick={() => setMobileMenuOpen(false)}>
                How it works
              </a>
              <a href="#plans" onClick={() => setMobileMenuOpen(false)}>
                Plans &amp; Pricing
              </a>
            </div>
            <div className="site-mobile-menu__actions">
              <Link
                className="btn btn--primary btn--block"
                to="/register"
                onClick={() => setMobileMenuOpen(false)}
              >
                Create an account
              </Link>
              <Link
                className="btn btn--block"
                to="/signin"
                onClick={() => setMobileMenuOpen(false)}
              >
                Sign in
              </Link>
            </div>
          </nav>
        )}
      </header>

      {/* ---------------------------------------------------------- hero */}
      <section className="hero">
        <div className="hero__inner">
          <div className="hero__copy">
            <span className="hero__eyebrow">
              <b>New</b> Excel in, seating plan out
            </span>
            <h1>
              Build a seating plan from <em>your own roster</em>.
            </h1>
            <p className="hero__lead">
              Upload the spreadsheet your office already keeps. The system works
              out how your students are identified, lays out the halls under
              the rules you set, and hands back a plan you can still move by
              hand.
            </p>
            <div className="hero__actions">
              <Link className="btn btn--primary btn--lg" to="/register">
                Get started — it is free
                <IconArrowRight width={16} height={16} />
              </Link>
              <Link className="btn btn--ghost btn--lg" to="/signin">
                I already have an account
              </Link>
            </div>
            <div className="hero__proof">
              <div>
                <span>2 min</span>
                <small>to your first plan</small>
              </div>
              <div>
                <span>Any</span>
                <small>Excel structure</small>
              </div>
              <div>
                <span>0</span>
                <small>questions to answer</small>
              </div>
            </div>
          </div>
          <div className="hero__art">
            <HeroArt />
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------ features */}
      <section className="section" id="features">
        <div className="section__head">
          <h2>Everything the exam cell needs, in one place</h2>
          <p>
            The roster, the halls, the plan, the exports and the people who
            need to see them.
          </p>
        </div>
        <div className="grid grid--3">
          {FEATURES.map((feature) => (
            <article className="feature" key={feature.title}>
              <span className="feature__icon">{feature.icon}</span>
              <h3>{feature.title}</h3>
              <p>{feature.body}</p>
            </article>
          ))}
        </div>
      </section>

      {/* ------------------------------------------------------ how it works */}
      <section className="section section--sunken" id="how">
        <div className="section__inner">
          <div className="section__head">
            <h2>Three steps, and none of them need a manual</h2>
            <p>From a spreadsheet to a plan that is ready to print.</p>
          </div>
          <div className="steps">
            {STEPS.map((step) => (
              <article className="step" key={step.title}>
                <h3>{step.title}</h3>
                <p>{step.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------ plans */}
      <section className="section" id="plans">
        <div className="section__head">
          <h2>Start free. Move up when you need to.</h2>
          <p>
            Every plan includes the roster, the halls, the seating plan and the
            exports.
          </p>
        </div>
        <div className="landing-plans-grid">
          {plans.map((plan) => {
            const featured = plan.code === featuredCode
            return (
              <article
                className={featured ? 'plan plan--featured' : 'plan'}
                key={plan.code}
              >
                {featured && <span className="plan__tag">Most chosen</span>}
                <h3 className="plan__name">{plan.name}</h3>
                <div className="plan__price">
                  {money(plan)}
                  {Number(plan.monthlyPrice || 0) > 0 && <small> / month</small>}
                </div>
                <ul>
                  <li>
                    <IconCheck width={15} height={15} />
                    {plan.maxStudents.toLocaleString()} students
                  </li>
                  <li>
                    <IconCheck width={15} height={15} />
                    {plan.maxRooms} halls
                  </li>
                  <li>
                    <IconCheck width={15} height={15} />
                    {plan.maxGenerationsPerMonth} plans a month
                  </li>
                  <li>
                    <IconCheck width={15} height={15} />
                    {plan.storageLimitMb} MB of storage
                  </li>
                  <li>
                    <IconCheck width={15} height={15} />
                    {plan.seatsPerGeneration.toLocaleString()} seats per plan
                  </li>
                </ul>
                <Link
                  className={featured ? 'btn btn--primary btn--block' : 'btn btn--block'}
                  to="/register"
                  style={{ marginTop: 'auto' }}
                >
                  Create an account
                </Link>
              </article>
            )
          })}
          {plans.length === 0 && (
            <p className="muted center" style={{ gridColumn: '1 / -1' }}>
              The plan catalogue is not available right now.
            </p>
          )}
        </div>
      </section>

      {/* ------------------------------------------------------ closing */}
      <section className="section section--sunken" style={{ textAlign: 'center' }}>
        <div className="section__inner">
          <div className="section__head" style={{ marginBottom: 'var(--space-8)' }}>
            <h2>Ready when your next exam is</h2>
            <p>
              Create an account and have a plan in front of you in a couple of
              minutes.
            </p>
          </div>
          <div className="closing-cta__actions">
            <Link className="btn btn--primary btn--lg" to="/register">
              Create your account
              <IconArrowRight width={16} height={16} />
            </Link>
            <Link className="btn btn--lg" to="/signin">
              Sign in
            </Link>
          </div>
        </div>
      </section>

      <footer className="site-footer">
        <div className="site-footer__inner">
          <div className="site-footer__brand">
            <Brand />
            <p>
              Seating arrangements built from the roster you already have — for
              schools, colleges and universities.
            </p>
          </div>
          <div>
            <h4>Product</h4>
            <a href="#features">What it does</a>
            <a href="#how">How it works</a>
            <a href="#plans">Plans</a>
          </div>
          <div>
            <h4>Account</h4>
            <Link to="/signin">Sign in</Link>
            <Link to="/register">Create an account</Link>
            <Link to="/admin/login">Platform administration</Link>
          </div>
        </div>
        <div className="site-footer__legal">
          Exam Seating Builder — seating arrangements from your own roster.
        </div>
      </footer>
    </div>
  )
}
