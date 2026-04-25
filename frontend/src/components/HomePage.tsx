/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Home Page Component - FULLY OPTIMIZED with Dynamic Imports
 * نبض الضاحية وقدسيا - الصفحة الرئيسية
 * 
 * Only Hero + QuickServices load immediately
 * Everything else loads on-demand
 */

'use client';

import { 
  AlertTriangle, BookOpen, Home, ShoppingBag, Heart, Users, Calendar, MapPin, Zap, Briefcase, Trophy, Building2, Sparkles, ChevronDown, ChevronUp, Scale, Car, Banknote, Shirt, Cake,
  UtensilsCrossed, Fuel, Stethoscope, Pill, GraduationCap, Wrench, Package, Flame, Utensils, HeartPulse, Scissors, Bus, Landmark, Tent
} from 'lucide-react';
import { Hero } from '@/components/ui';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from '@/components/local/RegionSelector';
import { FeaturedOffers } from '@/components/local';
import QuickServices from '@/components/local/QuickServices';
import dynamic from 'next/dynamic';
import { useState } from 'react';
import Link from 'next/link';

// Quick Icons Component for each section
function QuickIcons({ items, activeSection, setActiveSection }: { 
  items: { id: string; name: string; nameEn: string; icon: React.ElementType }[];
  activeSection: string | null;
  setActiveSection: (id: string | null) => void;
}) {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  
  return (
    <div className="flex gap-2 overflow-x-auto pb-2 mb-4 scrollbar-hide">
      {items.map((item) => {
        const Icon = item.icon;
        const isActive = activeSection === item.id;
        return (
          <button
            key={item.id}
            onClick={() => setActiveSection(isActive ? null : item.id)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap transition-colors ${
              isActive 
                ? 'bg-gray-900 text-white' 
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}
          >
            <Icon className="w-3.5 h-3.5" />
            <span>{isArabic ? item.name : item.nameEn}</span>
          </button>
        );
      })}
    </div>
  );
}

// Loading skeleton for sections
function SectionSkeleton() {
  return (
    <div className="py-4 animate-pulse">
      <div className="max-w-7xl mx-auto px-4">
        <div className="flex gap-4 overflow-hidden">
          {[1, 2, 3].map((i) => (
            <div key={i} className="flex-shrink-0 w-[240px]">
              <div className="bg-gray-200 rounded-xl aspect-[4/3] mb-2"></div>
              <div className="bg-gray-200 h-4 rounded w-3/4 mb-2"></div>
              <div className="bg-gray-200 h-3 rounded w-1/2"></div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// 🔄 LAZY LOAD: Everything else - loaded only when needed
const UrgentServices = dynamic(() => import('@/components/local/UrgentServices'), { loading: () => <SectionSkeleton />, ssr: false });
const EmergencyContacts = dynamic(() => import('@/components/local/EmergencyContacts'), { loading: () => <SectionSkeleton />, ssr: false });
const Restaurants = dynamic(() => import('@/components/local/Restaurants'), { loading: () => <SectionSkeleton />, ssr: false });
const Cafes = dynamic(() => import('@/components/local/Cafes'), { loading: () => <SectionSkeleton />, ssr: false });
const Markets = dynamic(() => import('@/components/local/Markets'), { loading: () => <SectionSkeleton />, ssr: false });
const MarketPrices = dynamic(() => import('@/components/local/MarketPrices'), { loading: () => <SectionSkeleton />, ssr: false });
const RetailShops = dynamic(() => import('@/components/local/RetailShops'), { loading: () => <SectionSkeleton />, ssr: false });

const CarServices = dynamic(() => import('@/components/local/CarServices'), { loading: () => <SectionSkeleton />, ssr: false });
const Pharmacies = dynamic(() => import('@/components/local/Pharmacies'), { loading: () => <SectionSkeleton />, ssr: false });
const MedicalCenters = dynamic(() => import('@/components/local/MedicalCenters'), { loading: () => <SectionSkeleton />, ssr: false });
const Jobs = dynamic(() => import('@/components/local/Jobs'), { loading: () => <SectionSkeleton />, ssr: false });
const RealEstate = dynamic(() => import('@/components/local/RealEstate'), { loading: () => <SectionSkeleton />, ssr: false });
const Professionals = dynamic(() => import('@/components/local/Professionals'), { loading: () => <SectionSkeleton />, ssr: false });
const Community = dynamic(() => import('@/components/local/Community'), { loading: () => <SectionSkeleton />, ssr: false });
const Charity = dynamic(() => import('@/components/local/Charity'), { loading: () => <SectionSkeleton />, ssr: false });
const Beauty = dynamic(() => import('@/components/local/Beauty'), { loading: () => <SectionSkeleton />, ssr: false });
const LaundryServices = dynamic(() => import('@/components/local/LaundryServices'), { loading: () => <SectionSkeleton />, ssr: false });
const Education = dynamic(() => import('@/components/local/Education'), { loading: () => <SectionSkeleton />, ssr: false });
const Sports = dynamic(() => import('@/components/local/Sports'), { loading: () => <SectionSkeleton />, ssr: false });
const Places = dynamic(() => import('@/components/local/Places'), { loading: () => <SectionSkeleton />, ssr: false });
const Hotels = dynamic(() => import('@/components/local/Hotels'), { loading: () => <SectionSkeleton />, ssr: false });
const EventServices = dynamic(() => import('@/components/local/EventServices'), { loading: () => <SectionSkeleton />, ssr: false });
const ServicesStatus = dynamic(() => import('@/components/local/ServicesStatus'), { loading: () => <SectionSkeleton />, ssr: false });
const GovernmentServices = dynamic(() => import('@/components/local/GovernmentServices'), { loading: () => <SectionSkeleton />, ssr: false });
const UsedItems = dynamic(() => import('@/components/local/UsedItems'), { loading: () => <SectionSkeleton />, ssr: false });
const Classifieds = dynamic(() => import('@/components/local/Classifieds'), { loading: () => <SectionSkeleton />, ssr: false });
const Craftsmen = dynamic(() => import('@/components/local/Craftsmen'), { loading: () => <SectionSkeleton />, ssr: false });
const Offices = dynamic(() => import('@/components/local/Offices'), { loading: () => <SectionSkeleton />, ssr: false });
const FinancialServices = dynamic(() => import('@/components/local/FinancialServices'), { loading: () => <SectionSkeleton />, ssr: false });
const Events = dynamic(() => import('@/components/local/Events'), { loading: () => <SectionSkeleton />, ssr: false });
const LocalNews = dynamic(() => import('@/components/local/LocalNews'), { loading: () => <SectionSkeleton />, ssr: false });

export function HomePage() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  
  // Active sections for quick icons
  const [activeMarketSection, setActiveMarketSection] = useState<string | null>(null);
  const [activeLocalSection, setActiveLocalSection] = useState<string | null>(null);
  const [activeCommunitySection, setActiveCommunitySection] = useState<string | null>(null);

  // Emergency section collapse state
  const [isEmergencyOpen, setIsEmergencyOpen] = useState(false);

  // Market Section Icons - تأكد من تحديث هذه القائمة عند أي تغيير
  const marketIcons = [
    { id: 'realestate', name: 'عقارات', nameEn: 'Real Estate', icon: Home },
    { id: 'used', name: 'مستعمل', nameEn: 'Used', icon: Package },
    { id: 'classifieds', name: 'إعلانات', nameEn: 'Classifieds', icon: ShoppingBag },
    { id: 'craftsmen', name: 'سوق الخدمات', nameEn: 'Services Market', icon: Wrench },
    { id: 'jobs', name: 'وظائف', nameEn: 'Jobs', icon: Briefcase },
  ];

  // Directory Groups Icons - للمجموعات الرئيسية
  const directoryGroupIcons = [
    { id: 'food', name: 'طعام وضيافة', nameEn: 'Food & Hospitality', icon: Utensils },
    { id: 'health', name: 'صحة وطبية', nameEn: 'Health & Medical', icon: HeartPulse },
    { id: 'beauty-group', name: 'تجميل وعناية', nameEn: 'Beauty & Care', icon: Scissors },
    { id: 'transport', name: 'سيارات ونقل', nameEn: 'Cars & Transport', icon: Car },
    { id: 'education-group', name: 'تعليم ورياضة', nameEn: 'Education & Sports', icon: GraduationCap },
    { id: 'tourism', name: 'سياحة وفنادق', nameEn: 'Tourism & Hotels', icon: Tent },
    { id: 'business', name: 'مهن ومكاتب', nameEn: 'Business & Offices', icon: Building2 },
    { id: 'public', name: 'خدمات عامة', nameEn: 'Public Services', icon: Landmark },
  ];

  // Community Section Icons - تأكد من تحديث هذه القائمة عند أي تغيير
  const communityIcons = [
    { id: 'community', name: 'المجتمع', nameEn: 'Community', icon: Users },
    { id: 'charity', name: 'خيرية', nameEn: 'Charity', icon: Heart },
    { id: 'events', name: 'فعاليات', nameEn: 'Events', icon: Calendar },
    { id: 'news', name: 'أخبار', nameEn: 'News', icon: BookOpen },
  ];

  // Market Sections Configuration - تأكد من تحديث هذه القائمة عند أي تغيير
  const marketSections = [
    { id: 'realestate', title: 'عقارات وإيجارات', titleEn: 'Real Estate', component: <RealEstate /> },
    { id: 'used', title: 'سلع مستعملة', titleEn: 'Used Items', component: <UsedItems /> },
    { id: 'classifieds', title: 'إعلانات مبوبة', titleEn: 'Classifieds', component: <Classifieds /> },
    { id: 'craftsmen', title: 'سوق الخدمات', titleEn: 'Services Market', component: <Craftsmen /> },
    { id: 'jobs', title: 'وظائف وفرص عمل', titleEn: 'Jobs & Opportunities', component: <Jobs /> },
  ];

  // Directory Groups Configuration - المجموعات الرئيسية
  const directoryGroups = [
    {
      id: 'food',
      title: '🍽️ طعام وضيافة',
      titleEn: '🍽️ Food & Hospitality',
      color: 'from-orange-500 to-amber-500',
      bgColor: 'bg-orange-50',
      borderColor: 'border-orange-200',
      sections: [
        { id: 'restaurants', title: 'مطاعم ومقاهي', titleEn: 'Restaurants & Cafes', component: <><Restaurants /><Cafes /></> },
        { id: 'markets', title: 'أسواق ومتاجر', titleEn: 'Markets & Stores', component: <><Markets /><MarketPrices /><RetailShops /></> },
      ]
    },
    {
      id: 'health',
      title: '🏥 صحة وطبية',
      titleEn: '🏥 Health & Medical',
      color: 'from-red-500 to-rose-500',
      bgColor: 'bg-red-50',
      borderColor: 'border-red-200',
      sections: [
        { id: 'pharmacies', title: 'صيدليات ومراكز طبية', titleEn: 'Pharmacies & Medical', component: <><Pharmacies /><MedicalCenters /></> },
      ]
    },
    {
      id: 'beauty-group',
      title: '💅 تجميل وعناية',
      titleEn: '💅 Beauty & Care',
      color: 'from-pink-500 to-purple-500',
      bgColor: 'bg-pink-50',
      borderColor: 'border-pink-200',
      sections: [
        { id: 'beauty', title: 'تجميل وعناية', titleEn: 'Beauty & Care', component: <Beauty /> },
        { id: 'laundry', title: 'مغاسل وتنظيف', titleEn: 'Laundry Services', component: <LaundryServices /> },
      ]
    },
    {
      id: 'transport',
      title: '🚗 سيارات ونقل',
      titleEn: '🚗 Cars & Transport',
      color: 'from-blue-500 to-cyan-500',
      bgColor: 'bg-blue-50',
      borderColor: 'border-blue-200',
      sections: [
        { id: 'car-services', title: 'خدمات سيارات', titleEn: 'Car Services', component: <CarServices /> },
      ]
    },
    {
      id: 'education-group',
      title: '📚 تعليم ورياضة',
      titleEn: '📚 Education & Sports',
      color: 'from-indigo-500 to-violet-500',
      bgColor: 'bg-indigo-50',
      borderColor: 'border-indigo-200',
      sections: [
        { id: 'education', title: 'تعليم ومدارس', titleEn: 'Education', component: <Education /> },
        { id: 'sports', title: 'مراكز رياضية', titleEn: 'Sports Centers', component: <Sports /> },
      ]
    },
    {
      id: 'tourism',
      title: '🏨 سياحة وفنادق',
      titleEn: '🏨 Tourism & Hotels',
      color: 'from-teal-500 to-emerald-500',
      bgColor: 'bg-teal-50',
      borderColor: 'border-teal-200',
      sections: [
        { id: 'places', title: 'سياحة وفنادق', titleEn: 'Tourism & Hotels', component: <><Places /><Hotels /></> },
        { id: 'event-services', title: 'خدمات المناسبات', titleEn: 'Event Services', component: <EventServices /> },
      ]
    },
    {
      id: 'business',
      title: '💼 مهن ومكاتب',
      titleEn: '💼 Business & Offices',
      color: 'from-slate-600 to-gray-700',
      bgColor: 'bg-slate-50',
      borderColor: 'border-slate-200',
      sections: [
        { id: 'professionals', title: 'المهن الحرة المتخصصة', titleEn: 'Professionals', component: <Professionals /> },
        { id: 'offices', title: 'مكاتب ووسطاء', titleEn: 'Offices', component: <Offices /> },
        { id: 'financial', title: 'خدمات مالية', titleEn: 'Financial Services', component: <FinancialServices /> },
      ]
    },
    {
      id: 'public',
      title: '🏛️ خدمات عامة',
      titleEn: '🏛️ Public Services',
      color: 'from-amber-500 to-yellow-500',
      bgColor: 'bg-amber-50',
      borderColor: 'border-amber-200',
      sections: [
        { id: 'government', title: 'خدمات حكومية', titleEn: 'Government Services', component: <GovernmentServices /> },
        { id: 'services-status', title: 'حالة الخدمات', titleEn: 'Services Status', component: <ServicesStatus /> },
      ]
    },
  ];

  // Community Sections Configuration - تأكد من تحديث هذه القائمة عند أي تغيير
  const communitySections = [
    { id: 'community', title: 'المجتمع', titleEn: 'Community', component: <Community /> },
    { id: 'charity', title: 'المؤسسات الخيرية', titleEn: 'Charity Organizations', component: <Charity /> },
    { id: 'events', title: 'فعاليات وأخبار', titleEn: 'Events & News', component: <><Events /><LocalNews /></> },
  ];

  // Helper function to reorder sections
  const getOrderedSections = (
    sections: { id: string; title: string; titleEn: string; component: React.ReactNode }[],
    activeId: string | null
  ) => {
    if (!activeId) return sections;
    const activeSection = sections.find(s => s.id === activeId);
    const otherSections = sections.filter(s => s.id !== activeId);
    return activeSection ? [activeSection, ...otherSections] : sections;
  };

  const orderedMarketSections = getOrderedSections(marketSections, activeMarketSection);
  // Helper to reorder groups based on active section
  const getOrderedGroups = (
    groups: typeof directoryGroups,
    activeGroupId: string | null
  ) => {
    if (!activeGroupId) return groups;
    const activeGroup = groups.find(g => g.id === activeGroupId);
    const otherGroups = groups.filter(g => g.id !== activeGroupId);
    return activeGroup ? [activeGroup, ...otherGroups] : groups;
  };

  const orderedDirectoryGroups = getOrderedGroups(directoryGroups, activeLocalSection);
  const orderedCommunitySections = getOrderedSections(communitySections, activeCommunitySection);

  return (
    <main className="min-h-screen bg-gray-50">
      {/* 🌟 Hero Section */}
      <Hero />

      {/* ⚡ Quick Services - الوصول السريع */}
      <QuickServices />

      {/* ═══════════════════════════════════════════════════════════════════════ */}
      {/* 🏠 الجزء الأول: السوق والإعلانات                                          */}
      {/* ═══════════════════════════════════════════════════════════════════════ */}
      <section id="market" className="mb-6 md:mb-8 bg-gradient-to-b from-teal-50 to-white py-8 md:py-12 lg:py-16">
        <div className="max-w-7xl mx-auto px-4 md:px-6 lg:px-8">
          {/* Part Header */}
          <div className="flex items-center gap-3 mb-4 md:mb-6 pb-3 md:pb-4 border-b-4 border-teal-500">
            <Link href="/market" className="p-3 bg-teal-500 rounded-xl hover:bg-teal-600 transition-colors">
              <Home className="w-6 h-6 text-white" />
            </Link>
            <Link href="/market" className="flex items-center gap-1 group">
              <h2 className="text-xl sm:text-2xl md:text-3xl font-black text-gray-900">
                {isArabic ? 'السوق والإعلانات' : 'Market & Classifieds'}
              </h2>
              <ChevronDown className="w-5 h-5 text-teal-500 rotate-90" />
            </Link>
          </div>

          {/* 🔥 عروض حصرية مدمجة - مصغرة */}
          <FeaturedOffers />

          {/* Quick Icons */}
          <QuickIcons 
            items={marketIcons} 
            activeSection={activeMarketSection} 
            setActiveSection={setActiveMarketSection} 
          />

          {/* Sections Grid - Dynamically Ordered */}
          <div className="space-y-4 md:space-y-6">
            {orderedMarketSections.map((section) => (
              <div key={section.id} className="mb-2">
                <Link href={`/${section.id}`} className="flex items-center justify-between group mb-3">
                  <h3 className="text-lg font-bold text-gray-900">
                    {isArabic ? section.title : section.titleEn}
                  </h3>
                  <ChevronDown className="w-4 h-4 text-gray-400 rotate-90" />
                </Link>
                <div id={section.id}>{section.component}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════════════════ */}
      {/* 📞 الجزء الثاني: دليل الطوارئ والمناوبات                                    */}
      {/* ═══════════════════════════════════════════════════════════════════════ */}
      <section className="mt-4 md:mt-6 mb-4 md:mb-6">
        <div className="max-w-7xl mx-auto px-4">
          {/* Part Header - قابل للنقر للفتح والإغلاق */}
          <button 
            onClick={() => setIsEmergencyOpen(!isEmergencyOpen)}
            className="flex items-center gap-3 mb-3 w-full text-right"
          >
            <div className="p-2 bg-gradient-to-br from-red-500 to-rose-600 rounded-xl shadow-md">
              <AlertTriangle className="w-5 h-5 text-white" />
            </div>
            <div className="flex-1">
              <h2 className="text-base font-black text-gray-900">
                {isArabic ? '📞 دليل الطوارئ' : '📞 Emergency Guide'}
              </h2>
              <p className="text-xs text-gray-500">
                {isArabic ? 'خدمات متاحة على مدار الساعة' : 'Services available 24/7'}
              </p>
            </div>
            <ChevronDown 
              className={`w-6 h-6 text-gray-500 transition-transform duration-300 ${
                isEmergencyOpen ? 'rotate-180' : ''
              }`}
            />
          </button>

          {/* Sections - تظهر فقط عند الفتح */}
          {isEmergencyOpen && (
            <div className="rounded-xl border border-red-100 bg-red-50/50 overflow-hidden animate-in fade-in slide-in-from-top-2 duration-300">
              <div id="emergency"><UrgentServices /></div>
              <div className="border-t border-red-100"><EmergencyContacts /></div>
            </div>
          )}
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════════════════ */}
      {/* 📱 الجزء الثالث: الدليل المحلي                                             */}
      {/* ═══════════════════════════════════════════════════════════════════════ */}
      <section id="directory" className="mb-6 md:mb-8 bg-gradient-to-b from-blue-50 to-white py-8 md:py-12 lg:py-16">
        <div className="max-w-7xl mx-auto px-4 md:px-6 lg:px-8">
          {/* Part Header */}
          <div className="flex items-center gap-3 mb-4 md:mb-6 pb-3 md:pb-4 border-b-4 border-blue-500">
            <Link href="/directory" className="p-3 bg-blue-500 rounded-xl hover:bg-blue-600 transition-colors">
              <MapPin className="w-6 h-6 text-white" />
            </Link>
            <Link href="/directory" className="flex items-center gap-1 group">
              <h2 className="text-xl sm:text-2xl md:text-3xl font-black text-gray-900">
                {isArabic ? 'الدليل المحلي' : 'Local Directory'}
              </h2>
              <ChevronDown className="w-5 h-5 text-blue-500 rotate-90" />
            </Link>
          </div>

          {/* Quick Icons for Groups */}
          <QuickIcons 
            items={directoryGroupIcons} 
            activeSection={activeLocalSection} 
            setActiveSection={setActiveLocalSection} 
          />

          {/* Directory Groups - Organized by Category */}
          <div className="space-y-6">
            {orderedDirectoryGroups.map((group) => (
              <div key={group.id} className="rounded-2xl border-2 overflow-hidden">
                {/* Group Header */}
                <div className={`flex items-center gap-3 p-4 ${group.bgColor} border-b-2 ${group.borderColor}`}>
                  <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${group.color} flex items-center justify-center shadow-md`}>
                    <span className="text-lg">{group.title.split(' ')[0]}</span>
                  </div>
                  <h3 className="text-lg font-bold text-gray-900">
                    {isArabic ? group.title.substring(group.title.indexOf(' ') + 1) : group.titleEn.substring(group.titleEn.indexOf(' ') + 1)}
                  </h3>
                </div>
                
                {/* Group Sections */}
                <div className="p-4 space-y-4 bg-white">
                  {group.sections.map((section) => (
                    <div key={section.id}>
                      <Link href={`/${section.id}`} className="flex items-center justify-between group mb-3">
                        <h4 className="text-base font-semibold text-gray-800 hover:text-blue-600 transition-colors">
                          {isArabic ? section.title : section.titleEn}
                        </h4>
                        <ChevronDown className="w-4 h-4 text-gray-400 rotate-90 group-hover:text-blue-500 transition-colors" />
                      </Link>
                      <div id={section.id}>{section.component}</div>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════════════════ */}
      {/* 👥 الجزء الرابع: المجتمع والأخبار                                          */}
      {/* ═══════════════════════════════════════════════════════════════════════ */}
      <section id="community" className="mb-8 md:mb-12 bg-gradient-to-b from-purple-50 to-white py-8 md:py-12 lg:py-16">
        <div className="max-w-7xl mx-auto px-4 md:px-6 lg:px-8">
          {/* Part Header */}
          <div className="flex items-center gap-3 mb-4 md:mb-6 pb-3 md:pb-4 border-b-4 border-purple-500">
            <Link href="/community" className="p-3 bg-purple-500 rounded-xl hover:bg-purple-600 transition-colors">
              <Users className="w-6 h-6 text-white" />
            </Link>
            <Link href="/community" className="flex items-center gap-1 group">
              <h2 className="text-xl sm:text-2xl md:text-3xl font-black text-gray-900">
                {isArabic ? 'المجتمع والأخبار' : 'Community & News'}
              </h2>
              <ChevronDown className="w-5 h-5 text-purple-500 rotate-90" />
            </Link>
          </div>

          {/* Quick Icons */}
          <QuickIcons 
            items={communityIcons} 
            activeSection={activeCommunitySection} 
            setActiveSection={setActiveCommunitySection} 
          />

          {/* Sections Grid - Dynamically Ordered */}
          <div className="space-y-4 md:space-y-6">
            {orderedCommunitySections.map((section) => (
              <div key={section.id} className="mb-2">
                <Link href={`/${section.id}`} className="flex items-center justify-between group mb-3">
                  <h3 className="text-lg font-bold text-gray-900">
                    {isArabic ? section.title : section.titleEn}
                  </h3>
                  <ChevronDown className="w-4 h-4 text-gray-400 rotate-90" />
                </Link>
                <div id={section.id}>{section.component}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

    </main>
  );
}
