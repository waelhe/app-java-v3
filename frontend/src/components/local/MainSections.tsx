'use client';

import React from 'react';
import { Store, TrendingUp, Flame, Users, MessageSquare, Briefcase, Heart, Home } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from './RegionSelector';
import { useRegion, Region } from '@/contexts/RegionContext';

interface Section {
  id: string;
  title: string;
  titleEn: string;
  subtitle: string;
  subtitleEn: string;
  icon: React.ElementType;
  stats: string;
  statsEn: string;
  color: string;
  bg: string;
  badge?: string;
}

const qudsayaCenterSections: Section[] = [
  {
    id: 'market',
    title: 'سوق قدسيا',
    titleEn: 'Qudsaya Market',
    subtitle: 'بيع وشراء كل شيء',
    subtitleEn: 'Buy & sell everything',
    icon: Store,
    stats: '+1,200 إعلان',
    statsEn: '+1,200 ads',
    color: 'from-orange-500 to-amber-500',
    bg: 'bg-orange-50',
    badge: 'ساخن 🔥'
  },
  {
    id: 'realestate',
    title: 'عقارات قدسيا',
    titleEn: 'Qudsaya Real Estate',
    subtitle: 'شقق، بيوت، مكاتب',
    subtitleEn: 'Apartments, houses, offices',
    icon: Home,
    stats: '+850 عقار',
    statsEn: '+850 properties',
    color: 'from-emerald-500 to-teal-500',
    bg: 'bg-emerald-50'
  },
  {
    id: 'jobs',
    title: 'وظائف قدسيا',
    titleEn: 'Qudsaya Jobs',
    subtitle: 'وظائف شاغرة',
    subtitleEn: 'Job vacancies',
    icon: Briefcase,
    stats: '+150 وظيفة',
    statsEn: '+150 jobs',
    color: 'from-blue-500 to-indigo-500',
    bg: 'bg-blue-50'
  },
  {
    id: 'community',
    title: 'مجتمع قدسيا',
    titleEn: 'Qudsaya Community',
    subtitle: 'فعاليات، أنشطة، تواصل',
    subtitleEn: 'Events, activities, connect',
    icon: Users,
    stats: '+5,000 عضو',
    statsEn: '+5,000 members',
    color: 'from-purple-500 to-pink-500',
    bg: 'bg-purple-50',
    badge: 'جديد ✨'
  }
];

const qudsayaDahiaSections: Section[] = [
  {
    id: 'market',
    title: 'سوق الضاحية',
    titleEn: 'Dahia Market',
    subtitle: 'بيع وشراء كل شيء',
    subtitleEn: 'Buy & sell everything',
    icon: Store,
    stats: '+950 إعلان',
    statsEn: '+950 ads',
    color: 'from-orange-500 to-amber-500',
    bg: 'bg-orange-50',
    badge: 'ساخن 🔥'
  },
  {
    id: 'realestate',
    title: 'عقارات الضاحية',
    titleEn: 'Dahia Real Estate',
    subtitle: 'شقق، بيوت، مكاتب',
    subtitleEn: 'Apartments, houses, offices',
    icon: Home,
    stats: '+620 عقار',
    statsEn: '+620 properties',
    color: 'from-emerald-500 to-teal-500',
    bg: 'bg-emerald-50'
  },
  {
    id: 'jobs',
    title: 'وظائف الضاحية',
    titleEn: 'Dahia Jobs',
    subtitle: 'وظائف شاغرة',
    subtitleEn: 'Job vacancies',
    icon: Briefcase,
    stats: '+120 وظيفة',
    statsEn: '+120 jobs',
    color: 'from-blue-500 to-indigo-500',
    bg: 'bg-blue-50'
  },
  {
    id: 'community',
    title: 'مجتمع الضاحية',
    titleEn: 'Dahia Community',
    subtitle: 'فعاليات، أنشطة، تواصل',
    subtitleEn: 'Events, activities, connect',
    icon: Users,
    stats: '+3,500 عضو',
    statsEn: '+3,500 members',
    color: 'from-purple-500 to-pink-500',
    bg: 'bg-purple-50',
    badge: 'جديد ✨'
  }
];

const dataByRegion: Record<Region, Section[]> = {
  'qudsaya-center': qudsayaCenterSections,
  'qudsaya-dahia': qudsayaDahiaSections
};

export default function MainSections() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const sections = dataByRegion[region];

  return (
    <section className="py-6 bg-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-3">
            <div className="p-3 bg-amber-600 rounded-2xl shadow-lg">
              <TrendingUp className="w-7 h-7 text-white" />
            </div>
            <div>
              <h2 className="text-lg font-black text-gray-900">
                {isArabic ? 'الأقسام الرئيسية' : 'Main Sections'}
              </h2>
              <p className="text-sm text-gray-500">
                {isArabic ? `كل ما تحتاجه في ${regionName}` : `Everything you need in ${regionName}`}
              </p>
            </div>
          </div>
          <RegionSelector />
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {sections.map((section, index) => {
            const Icon = section.icon;
            return (
              <motion.div
                key={section.id}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: index * 0.1 }}
                className="group relative bg-white rounded-2xl border-2 border-gray-200 hover:shadow-xl transition-all overflow-hidden cursor-pointer"
              >
                {/* Gradient Top */}
                <div className={`h-2 bg-gradient-to-r ${section.color}`} />
                
                <div className="p-5">
                  {section.badge && (
                    <span className="absolute top-4 right-4 px-2 py-1 bg-rose-500 text-white text-[10px] font-bold rounded-full">
                      {section.badge}
                    </span>
                  )}

                  <div className={`inline-flex p-3 rounded-2xl ${section.bg} mb-4 group-hover:scale-110 transition-transform`}>
                    <Icon className={`w-7 h-7 ${section.color.replace('from-', 'text-').split(' ')[0]}`} />
                  </div>

                  <h3 className="text-lg font-bold text-gray-900 mb-1">
                    {isArabic ? section.title : section.titleEn}
                  </h3>
                  <p className="text-sm text-gray-500 mb-3">
                    {isArabic ? section.subtitle : section.subtitleEn}
                  </p>

                  <div className="flex items-center gap-1 text-sm text-emerald-600 font-bold">
                    <TrendingUp className="w-4 h-4" />
                    {isArabic ? section.stats : section.statsEn}
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
