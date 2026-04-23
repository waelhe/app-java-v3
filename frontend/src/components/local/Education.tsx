'use client';

import React, { useState, useRef, useEffect } from 'react';
import { GraduationCap, Users, BookOpen, Star, Heart, ChevronLeft, ChevronRight, School, Building2, BookOpenCheck, Baby } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface School {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  level: string;
  levelEn: string;
  students: number;
  rating: number;
  image: string;
  isEnrolling: boolean;
  category: 'school' | 'institute' | 'course' | 'kindergarten';
  featured?: boolean;
}

const qudsayaCenterSchools: School[] = [
  { id: '1', name: 'مدرسة قدسيا النموذجية', nameEn: 'Qudsaya Model School', type: 'حكومية', typeEn: 'Public', level: 'ابتدائي - ثانوي', levelEn: 'Primary - Secondary', students: 900, rating: 4.8, image: 'https://images.unsplash.com/photo-1580582932707-520aed937b7b?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'school', featured: true },
  { id: '2', name: 'روضة قدسيا', nameEn: 'Qudsaya Kindergarten', type: 'خاصة', typeEn: 'Private', level: 'رياض أطفال', levelEn: 'Kindergarten', students: 150, rating: 4.9, image: 'https://images.unsplash.com/photo-1503676260728-1c00da094a0b?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'kindergarten' },
  { id: '3', name: 'معهد اللغات - قدسيا', nameEn: 'Languages Institute - Qudsaya', type: 'خاصة', typeEn: 'Private', level: 'دورات لغات', levelEn: 'Language Courses', students: 400, rating: 4.6, image: 'https://images.unsplash.com/photo-1546410531-bb4caa6b424d?auto=format&fit=crop&w=400&q=80', isEnrolling: false, category: 'institute', featured: true },
  { id: '4', name: 'مركز التفوق - قدسيا', nameEn: 'Excellence Center - Qudsaya', type: 'خاصة', typeEn: 'Private', level: 'دروس خصوصية', levelEn: 'Private Tutoring', students: 250, rating: 4.7, image: 'https://images.unsplash.com/photo-1523050854058-8df90110c9f1?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'course' },
  { id: '5', name: 'معهد التقنية الحديثة', nameEn: 'Modern Technology Institute', type: 'خاصة', typeEn: 'Private', level: 'دورات برمجة', levelEn: 'Programming Courses', students: 180, rating: 4.5, image: 'https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'institute' },
  { id: '6', name: 'مدرسة الفجر النموذجية', nameEn: 'Al-Fajr Model School', type: 'خاصة', typeEn: 'Private', level: 'ابتدائي - إعدادي', levelEn: 'Primary - Preparatory', students: 650, rating: 4.9, image: 'https://images.unsplash.com/photo-1580582932707-520aed937b7b?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'school' },
];

const qudsayaDahiaSchools: School[] = [
  { id: '1', name: 'مدرسة الضاحية النموذجية', nameEn: 'Dahia Model School', type: 'حكومية', typeEn: 'Public', level: 'ابتدائي - ثانوي', levelEn: 'Primary - Secondary', students: 850, rating: 4.7, image: 'https://images.unsplash.com/photo-1580582932707-520aed937b7b?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'school', featured: true },
  { id: '2', name: 'روضة الأمل - الضاحية', nameEn: 'Al-Amal Kindergarten - Dahia', type: 'خاصة', typeEn: 'Private', level: 'رياض أطفال', levelEn: 'Kindergarten', students: 120, rating: 4.9, image: 'https://images.unsplash.com/photo-1503676260728-1c00da094a0b?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'kindergarten' },
  { id: '3', name: 'معهد اللغات - الضاحية', nameEn: 'Languages Institute - Dahia', type: 'خاصة', typeEn: 'Private', level: 'دورات لغات', levelEn: 'Language Courses', students: 350, rating: 4.5, image: 'https://images.unsplash.com/photo-1546410531-bb4caa6b424d?auto=format&fit=crop&w=400&q=80', isEnrolling: false, category: 'institute' },
  { id: '4', name: 'مركز التفوق - الضاحية', nameEn: 'Excellence Center - Dahia', type: 'خاصة', typeEn: 'Private', level: 'دروس خصوصية', levelEn: 'Private Tutoring', students: 200, rating: 4.8, image: 'https://images.unsplash.com/photo-1523050854058-8df90110c9f1?auto=format&fit=crop&w=400&q=80', isEnrolling: true, category: 'course', featured: true },
];

const dataByRegion: Record<Region, School[]> = {
  'qudsaya-center': qudsayaCenterSchools,
  'qudsaya-dahia': qudsayaDahiaSchools
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: GraduationCap },
  { id: 'school', name: 'مدارس', nameEn: 'Schools', icon: School },
  { id: 'institute', name: 'معاهد', nameEn: 'Institutes', icon: Building2 },
  { id: 'course', name: 'دورات', nameEn: 'Courses', icon: BookOpenCheck },
  { id: 'kindergarten', name: 'رياض أطفال', nameEn: 'Kindergarten', icon: Baby },
];

export default function Education() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const schools = dataByRegion[region];

  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredSchools = schools.filter(school => activeCategory === 'all' || school.category === activeCategory);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredSchools]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '📚 تعليم' : '📚 Education'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredSchools.slice(0, 8).map((school) => {
              const isFavorite = favorites.includes(school.id);
              return (
                <div key={school.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={school.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(school.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${school.type === 'حكومية' ? 'bg-blue-500/90 text-white' : 'bg-amber-500/90 text-white'}`}>
                      {isArabic ? school.type : school.typeEn}
                    </span>
                    {school.isEnrolling && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-emerald-500/90 text-white">
                        {isArabic ? 'تسجيل مفتوح' : 'Enrolling'}
                      </span>
                    )}
                    {school.featured && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-white/90 text-gray-900">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-[10px] text-gray-500 font-medium flex items-center gap-0.5">
                        <BookOpen className="w-3 h-3" /> {isArabic ? school.level : school.levelEn}
                      </span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{school.rating}</span>
                      </div>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-2">{isArabic ? school.name : school.nameEn}</h3>
                    <div className="flex items-center gap-0.5 text-xs text-gray-500">
                      <Users className="w-3 h-3" />
                      <span>{school.students}+ {isArabic ? 'طالب' : 'students'}</span>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredSchools.slice(0, 4).map((s, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={s.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredSchools.length} {isArabic ? 'مؤسسة' : 'schools'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع المؤسسات التعليمية' : 'All Educational Institutions'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredSchools.length} {isArabic ? 'مؤسسة متاحة' : 'schools available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredSchools.map((school) => {
                const isFavorite = favorites.includes(school.id);
                return (
                  <div key={school.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={school.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(school.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${school.type === 'حكومية' ? 'bg-blue-500/90 text-white' : 'bg-amber-500/90 text-white'}`}>
                        {isArabic ? school.type : school.typeEn}
                      </span>
                    </div>
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-[10px] text-gray-500 font-medium flex items-center gap-0.5">
                        <BookOpen className="w-3 h-3" /> {isArabic ? school.level : school.levelEn}
                      </span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{school.rating}</span>
                      </div>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? school.name : school.nameEn}</h3>
                    <div className="flex items-center gap-0.5 text-xs text-gray-500">
                      <Users className="w-3 h-3" />
                      <span>{school.students}+ {isArabic ? 'طالب' : 'students'}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
