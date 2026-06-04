package io.conddo.studio.builder;

import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.HomePageRequiredException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.common.VersionMismatchException;
import io.conddo.studio.domain.Job;
import io.conddo.studio.domain.Site;
import io.conddo.studio.domain.SitePage;
import io.conddo.studio.domain.SiteSection;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.repository.SitePageRepository;
import io.conddo.studio.repository.SiteRepository;
import io.conddo.studio.repository.SiteSectionRepository;
import io.conddo.studio.sse.JobLifecycleEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Website Builder facade (Infrastructure §21). One {@link Site} per {@link Job},
 * created lazily on first PUT. Pages are children, sections are grandchildren;
 * everything mutates through this service so version bumps, SSE events, and
 * access checks stay in one place.
 *
 * <p>Authorisation: the caller must be the job's assignee, or hold TEAM_LEAD
 * or ADMIN. Controller-level {@code @PreAuthorize} keeps everyone else off the
 * endpoint; the service-side check pins assignee-only for developers.
 *
 * <p>Optimistic locking: {@code Site.version} is JPA-managed. Mutations that
 * touch the site row directly bump it via Hibernate; mutations that touch a
 * child (page or section) bump it via {@link #touchSite}, so the FE's
 * {@code If-Match} stays meaningful for every write.
 */
@Service
public class SiteService {

    /** Soft caps so a runaway client can't fill the DB with junk. */
    private static final int MAX_PAGES_PER_SITE = 50;
    private static final int MAX_SECTIONS_PER_PAGE = 50;

    private final SiteRepository sites;
    private final SitePageRepository pages;
    private final SiteSectionRepository sections;
    private final JobRepository jobs;
    private final ApplicationEventPublisher events;

    public SiteService(SiteRepository sites, SitePageRepository pages, SiteSectionRepository sections,
                       JobRepository jobs, ApplicationEventPublisher events) {
        this.sites = sites;
        this.pages = pages;
        this.sections = sections;
        this.jobs = jobs;
        this.events = events;
    }

    // ----- reads --------------------------------------------------------------

    @Transactional(readOnly = true)
    public SiteView get(UUID jobId, UUID callerStaffId, String callerRole) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = sites.findByJobId(jobId)
                .orElseThrow(() -> new NotFoundException("No site for job " + jobId + " — call PUT to create"));
        return assemble(job, site);
    }

    // ----- PUT (full replace + lazy create) ----------------------------------

    /**
     * Lazy-create or fully replace the site for a job. The expected version is
     * the {@code If-Match} header — pass {@code null} on the first PUT
     * (no site yet) and the current version on every later PUT.
     */
    @Transactional
    public SiteView putSite(UUID jobId, UUID callerStaffId, String callerRole,
                            Integer expectedVersion,
                            Map<String, Object> theme,
                            Map<String, Object> meta,
                            List<PageInput> pageInputs) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = sites.findByJobId(jobId).orElse(null);

        if (site == null) {
            // First PUT — caller doesn't have a version to match yet.
            site = sites.save(new Site(jobId));
        } else {
            requireVersion(site, expectedVersion);
            // Replace state — drop all existing pages and sections (cascades on delete).
            for (SitePage existing : pages.findBySiteIdOrderByOrderIndexAsc(site.getId())) {
                pages.deleteById(existing.getId());
            }
            pages.flush();
        }

        if (theme != null) {
            site.setTheme(theme);
        }
        if (meta != null) {
            site.setMeta(meta);
        }

        // Recreate pages + sections in the order provided. Exactly one home page.
        if (pageInputs != null && !pageInputs.isEmpty()) {
            if (pageInputs.size() > MAX_PAGES_PER_SITE) {
                throw new IllegalArgumentException("Too many pages — cap is " + MAX_PAGES_PER_SITE);
            }
            long homeCount = pageInputs.stream().filter(PageInput::home).count();
            if (homeCount != 1) {
                throw new IllegalArgumentException("Exactly one page must be marked home, got " + homeCount);
            }
            int pageOrder = 0;
            for (PageInput pi : pageInputs) {
                SitePage saved = pages.save(new SitePage(site.getId(),
                        normaliseSlug(pi.slug()), pi.title(), pi.home(), pageOrder++));
                if (pi.sections() != null) {
                    if (pi.sections().size() > MAX_SECTIONS_PER_PAGE) {
                        throw new IllegalArgumentException(
                                "Too many sections on page " + saved.getSlug() + " — cap is " + MAX_SECTIONS_PER_PAGE);
                    }
                    int sectionOrder = 0;
                    for (SectionInput si : pi.sections()) {
                        String type = SectionContentValidator.validate(si.type(), si.content());
                        sections.save(new SiteSection(saved.getId(), type, si.content(), sectionOrder++));
                    }
                }
            }
        }

        Site touched = sites.save(site);
        events.publishEvent(new JobLifecycleEvent.SiteSectionUpdated(
                job.getId(), job.getJobNumber(), null, null, touched.getVersion()));
        return assemble(job, touched);
    }

    // ----- PATCH theme --------------------------------------------------------

    @Transactional
    public SiteView patchTheme(UUID jobId, UUID callerStaffId, String callerRole,
                               Integer expectedVersion, Map<String, Object> themeUpdates) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        site.mergeTheme(themeUpdates);
        Site saved = sites.save(site);
        return assemble(job, saved);
    }

    // ----- pages --------------------------------------------------------------

    @Transactional
    public SitePage addPage(UUID jobId, UUID callerStaffId, String callerRole,
                            Integer expectedVersion, String slug, String title, boolean home, Integer order) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        if (pages.countBySiteId(site.getId()) >= MAX_PAGES_PER_SITE) {
            throw new IllegalArgumentException("Too many pages — cap is " + MAX_PAGES_PER_SITE);
        }
        String normalisedSlug = normaliseSlug(slug);
        if (pages.findBySiteIdAndSlug(site.getId(), normalisedSlug).isPresent()) {
            throw new ConflictException("A page with slug '" + normalisedSlug + "' already exists");
        }
        // If the new page claims home, demote whoever was home before.
        if (home) {
            pages.findBySiteIdAndHomeTrue(site.getId()).ifPresent(existingHome -> {
                existingHome.setHome(false);
                pages.save(existingHome);
            });
        } else if (pages.countBySiteId(site.getId()) == 0) {
            // First page on a fresh site is implicitly home so the integrity rule holds.
            home = true;
        }
        int resolvedOrder = order != null ? order : (int) pages.countBySiteId(site.getId());
        SitePage saved = pages.save(new SitePage(site.getId(), normalisedSlug,
                title == null || title.isBlank() ? normalisedSlug : title, home, resolvedOrder));
        touchSite(site);
        return saved;
    }

    @Transactional
    public SitePage patchPage(UUID jobId, UUID pageId, UUID callerStaffId, String callerRole,
                              Integer expectedVersion, String slug, String title, Integer order, Boolean home) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        SitePage page = pages.findById(pageId)
                .filter(p -> p.getSiteId().equals(site.getId()))
                .orElseThrow(() -> new NotFoundException("Page not found on this site"));

        if (slug != null && !slug.isBlank()) {
            String normalised = normaliseSlug(slug);
            if (!normalised.equals(page.getSlug())
                    && pages.findBySiteIdAndSlug(site.getId(), normalised).isPresent()) {
                throw new ConflictException("A page with slug '" + normalised + "' already exists");
            }
            page.resluggify(normalised);
        }
        if (title != null) {
            page.rename(title);
        }
        if (order != null) {
            page.setOrderIndex(order);
        }
        if (home != null) {
            if (home && !page.isHome()) {
                pages.findBySiteIdAndHomeTrue(site.getId()).ifPresent(prev -> {
                    prev.setHome(false);
                    pages.save(prev);
                });
                page.setHome(true);
            } else if (!home && page.isHome()) {
                throw new HomePageRequiredException();
            }
        }
        SitePage saved = pages.save(page);
        touchSite(site);
        return saved;
    }

    @Transactional
    public void deletePage(UUID jobId, UUID pageId, UUID callerStaffId, String callerRole,
                           Integer expectedVersion) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        SitePage page = pages.findById(pageId)
                .filter(p -> p.getSiteId().equals(site.getId()))
                .orElseThrow(() -> new NotFoundException("Page not found on this site"));
        if (page.isHome()) {
            throw new HomePageRequiredException();
        }
        pages.deleteById(pageId);
        touchSite(site);
    }

    // ----- sections -----------------------------------------------------------

    @Transactional
    public SiteSection addSection(UUID jobId, UUID pageId, UUID callerStaffId, String callerRole,
                                  Integer expectedVersion, String type, Map<String, Object> content, Integer order) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        SitePage page = pages.findById(pageId)
                .filter(p -> p.getSiteId().equals(site.getId()))
                .orElseThrow(() -> new NotFoundException("Page not found on this site"));
        long currentSectionCount = sections.findByPageIdOrderByOrderIndexAsc(pageId).size();
        if (currentSectionCount >= MAX_SECTIONS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "Too many sections on this page — cap is " + MAX_SECTIONS_PER_PAGE);
        }
        String normalisedType = SectionContentValidator.validate(type, content);
        int resolvedOrder = order != null ? order : (int) currentSectionCount;
        SiteSection saved = sections.save(new SiteSection(page.getId(), normalisedType, content, resolvedOrder));
        Site refreshed = touchSite(site);
        events.publishEvent(new JobLifecycleEvent.SiteSectionUpdated(
                job.getId(), job.getJobNumber(), pageId, saved.getId(), refreshed.getVersion()));
        return saved;
    }

    @Transactional
    public SiteSection patchSection(UUID jobId, UUID pageId, UUID sectionId,
                                    UUID callerStaffId, String callerRole,
                                    Integer expectedVersion, Map<String, Object> content, Integer order) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        SiteSection section = sections.findById(sectionId)
                .filter(s -> s.getPageId().equals(pageId))
                .orElseThrow(() -> new NotFoundException("Section not found on this page"));
        if (content != null) {
            SectionContentValidator.validate(section.getSectionType(), content);
            section.setContent(content);
        }
        if (order != null) {
            section.setOrderIndex(order);
        }
        SiteSection saved = sections.save(section);
        Site refreshed = touchSite(site);
        events.publishEvent(new JobLifecycleEvent.SiteSectionUpdated(
                job.getId(), job.getJobNumber(), pageId, saved.getId(), refreshed.getVersion()));
        return saved;
    }

    @Transactional
    public void deleteSection(UUID jobId, UUID pageId, UUID sectionId,
                              UUID callerStaffId, String callerRole, Integer expectedVersion) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        requireVersion(site, expectedVersion);
        SiteSection section = sections.findById(sectionId)
                .filter(s -> s.getPageId().equals(pageId))
                .orElseThrow(() -> new NotFoundException("Section not found on this page"));
        sections.deleteById(section.getId());
        Site refreshed = touchSite(site);
        events.publishEvent(new JobLifecycleEvent.SiteSectionUpdated(
                job.getId(), job.getJobNumber(), pageId, sectionId, refreshed.getVersion()));
    }

    // ----- publish ------------------------------------------------------------

    @Transactional
    public SiteView publish(UUID jobId, UUID callerStaffId, String callerRole) {
        Job job = requireJob(jobId);
        requireWriteAccess(job, callerStaffId, callerRole);
        Site site = requireSite(jobId);
        site.publish(OffsetDateTime.now());
        Site saved = sites.save(site);
        events.publishEvent(new JobLifecycleEvent.SitePublished(
                job.getId(), job.getJobNumber(), saved.getVersion(), saved.getPublishedAt()));
        return assemble(job, saved);
    }

    /**
     * Internal auto-publish, called from {@code JobService.submit}. No caller
     * check (the job's lifecycle already enforced who can submit). No-op if no
     * site exists — submission is still allowed when the dev built externally
     * and just submitted a URL.
     */
    @Transactional
    public void autoPublishIfPresent(UUID jobId) {
        sites.findByJobId(jobId).ifPresent(site -> {
            if (!"PUBLISHED".equals(site.getStatus())) {
                site.publish(OffsetDateTime.now());
                Site saved = sites.save(site);
                Job job = jobs.findById(jobId).orElse(null);
                if (job != null) {
                    events.publishEvent(new JobLifecycleEvent.SitePublished(
                            job.getId(), job.getJobNumber(), saved.getVersion(), saved.getPublishedAt()));
                }
            }
        });
    }

    // ----- internals ----------------------------------------------------------

    private Site touchSite(Site site) {
        // Re-save the site with no real change just to bump @Version on every
        // child write. mergeTheme with empty map is a no-op for content but
        // marks the entity dirty.
        site.mergeTheme(Map.of("__touchedAt", System.nanoTime()));
        site.getTheme().remove("__touchedAt");
        return sites.save(site);
    }

    private Site requireSite(UUID jobId) {
        return sites.findByJobId(jobId)
                .orElseThrow(() -> new NotFoundException("No site for job " + jobId));
    }

    private Job requireJob(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Job not found"));
    }

    private static void requireWriteAccess(Job job, UUID callerStaffId, String callerRole) {
        if ("TEAM_LEAD".equals(callerRole) || "ADMIN".equals(callerRole)) {
            return;
        }
        if (callerStaffId != null && callerStaffId.equals(job.getAssignedTo())) {
            return;
        }
        throw new ConflictException("Builder is restricted to the assignee, TEAM_LEAD, or ADMIN");
    }

    private static void requireVersion(Site site, Integer expected) {
        if (expected == null) {
            return;   // unconditional — caller chose not to optimistic-lock
        }
        if (site.getVersion() != expected.intValue()) {
            throw new VersionMismatchException(expected, site.getVersion());
        }
    }

    private static String normaliseSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return "home";
        }
        return slug.trim().toLowerCase().replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
    }

    private SiteView assemble(Job job, Site site) {
        List<SitePage> pageRows = pages.findBySiteIdOrderByOrderIndexAsc(site.getId());
        List<PageView> pageViews = new ArrayList<>();
        for (SitePage page : pageRows) {
            List<SiteSection> sectionRows = sections.findByPageIdOrderByOrderIndexAsc(page.getId());
            sectionRows.sort(Comparator.comparingInt(SiteSection::getOrderIndex));
            pageViews.add(new PageView(page, sectionRows));
        }
        return new SiteView(job, site, pageViews);
    }

    // ----- view records (assembled by the controller into the DTO) -----------

    public record SiteView(Job job, Site site, List<PageView> pages) {
    }

    public record PageView(SitePage page, List<SiteSection> sections) {
    }

    public record PageInput(String slug, String title, boolean home, List<SectionInput> sections) {
    }

    public record SectionInput(String type, Map<String, Object> content) {
    }
}
