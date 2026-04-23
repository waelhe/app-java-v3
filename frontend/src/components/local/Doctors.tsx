'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Heart, Star, Calendar, ChevronLeft, ChevronRight, Stethoscope, Baby, Smile, Sparkles, Activity, Users } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Doctor {
  id: string;
  name: string;
  nameEn: string;
  specialty: string;
  specialtyEn: string;
  specialtyCategory: 'general' | 'pediatrics' | 'dentistry' | 'dermatology' | 'gynecology' | 'cardiology';
  rating: number;
  reviews: number;
  nextAvailable: string;
  image: string;
  isAvailable: boolean;
  featured?: boolean;
  new?: boolean;
}

const qudsayaDahiaDoctors: Doctor[] = [
  {
    id: '1',
    name: 'د. أحمد العمري',
    nameEn: 'Dr. Ahmed Al-Omari',
    specialty: 'طب عام',
    specialtyEn: 'General Medicine',
    specialtyCategory: 'general',
    rating: 4.9,
    reviews: 156,
    nextAvailable: '10:00 ص',
    image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    featured: true
  },
  {
    id: '2',
    name: 'د. سارة الخالد',
    nameEn: 'Dr. Sara Al-Khaled',
    specialty: 'طب أطفال',
    specialtyEn: 'Pediatrics',
    specialtyCategory: 'pediatrics',
    rating: 4.8,
    reviews: 98,
    nextAvailable: '11:30 ص',
    image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    new: true
  },
  {
    id: '3',
    name: 'د. محمد العلي',
    nameEn: 'Dr. Mohammad Al-Ali',
    specialty: 'طب أسنان',
    specialtyEn: 'Dentistry',
    specialtyCategory: 'dentistry',
    rating: 4.7,
    reviews: 124,
    nextAvailable: '2:00 م',
    image: 'https://images.unsplash.com/photo-1622253692010-333f2da6031d?auto=format&fit=crop&w=400&q=80',
    isAvailable: true
  },
  {
    id: '4',
    name: 'د. ليلى حسن',
    nameEn: 'Dr. Laila Hassan',
    specialty: 'أمراض جلدية',
    specialtyEn: 'Dermatology',
    specialtyCategory: 'dermatology',
    rating: 4.6,
    reviews: 87,
    nextAvailable: '4:00 م',
    image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    featured: true
  },
  {
    id: '5',
    name: 'د. خالد المحمود',
    nameEn: 'Dr. Khaled Al-Mahmoud',
    specialty: 'طب قلب',
    specialtyEn: 'Cardiology',
    specialtyCategory: 'cardiology',
    rating: 4.9,
    reviews: 201,
    nextAvailable: '9:00 ص',
    image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    new: true
  },
  {
    id: '6',
    name: 'د. نورا الأحمد',
    nameEn: 'Dr. Noura Al-Ahmad',
    specialty: 'طب نسائية',
    specialtyEn: 'Gynecology',
    specialtyCategory: 'gynecology',
    rating: 4.8,
    reviews: 178,
    nextAvailable: '1:30 م',
    image: 'https://images.unsplash.com/photo-1651008376811-b90baee60c1f?auto=format&fit=crop&w=400&q=80',
    isAvailable: true
  }
];

const qudsayaCenterDoctors: Doctor[] = [
  {
    id: 'q1',
    name: 'د. محمود القدسي',
    nameEn: 'Dr. Mahmoud Al-Qudsi',
    specialty: 'طب عام',
    specialtyEn: 'General Medicine',
    specialtyCategory: 'general',
    rating: 4.8,
    reviews: 134,
    nextAvailable: '9:30 ص',
    image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80',
    isAvailable: true
  },
  {
    id: 'q2',
    name: 'د. نورة الأحمد',
    nameEn: 'Dr. Noura Al-Ahmad',
    specialty: 'طب أطفال',
    specialtyEn: 'Pediatrics',
    specialtyCategory: 'pediatrics',
    rating: 4.9,
    reviews: 167,
    nextAvailable: '10:00 ص',
    image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    featured: true
  },
  {
    id: 'q3',
    name: 'د. خالد المحمود',
    nameEn: 'Dr. Khaled Al-Mahmoud',
    specialty: 'طب أسنان',
    specialtyEn: 'Dentistry',
    specialtyCategory: 'dentistry',
    rating: 4.7,
    reviews: 89,
    nextAvailable: '1:00 م',
    image: 'https://images.unsplash.com/photo-1622253692010-333f2da6031d?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    new: true
  },
  {
    id: 'q4',
    name: 'د. رنا السيد',
    nameEn: 'Dr. Rana Al-Sayed',
    specialty: 'طب نسائية',
    specialtyEn: 'Gynecology',
    specialtyCategory: 'gynecology',
    rating: 4.8,
    reviews: 145,
    nextAvailable: '3:30 م',
    image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    featured: true
  },
  {
    id: 'q5',
    name: 'د. فاطمة الزهراء',
    nameEn: 'Dr. Fatima Al-Zahra',
    specialty: 'أمراض جلدية',
    specialtyEn: 'Dermatology',
    specialtyCategory: 'dermatology',
    rating: 4.6,
    reviews: 76,
    nextAvailable: '11:00 ص',
    image: 'https://images.unsplash.com/photo-1651008376811-b90baee60c1f?auto=format&fit=crop&w=400&q=80',
    isAvailable: true
  },
  {
    id: 'q6',
    name: 'د. حسن الكردي',
    nameEn: 'Dr. Hassan Al-Kurdi',
    specialty: 'طب قلب',
    specialtyEn: 'Cardiology',
    specialtyCategory: 'cardiology',
    rating: 4.9,
    reviews: 198,
    nextAvailable: '2:30 م',
    image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?auto=format&fit=crop&w=400&q=80',
    isAvailable: true,
    new: true
  }
];

const doctorsByRegion: Record<Region, Doctor[]> = {
  'qudsaya-center': qudsayaCenterDoctors,
  'qudsaya-dahia': qudsayaDahiaDoctors
};

const specialtyFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Stethoscope },
  { id: 'general', name: 'طب عام', nameEn: 'General', icon: Activity },
  { id: 'pediatrics', name: 'أطفال', nameEn: 'Pediatrics', icon: Baby },
  { id: 'dentistry', name: 'أسنان', nameEn: 'Dentistry', icon: Smile },
  { id: 'dermatology', name: 'جلدية', nameEn: 'Dermatology', icon: Sparkles },
  { id: 'gynecology', name: 'نسائية', nameEn: 'Gynecology', icon: Users },
  { id: 'cardiology', name: 'قلب', nameEn: 'Cardiology', icon: Heart },
];

export default function Doctors() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const doctors = doctorsByRegion[region];

  const [activeSpecialty, setActiveSpecialty] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredDoctors = doctors.filter(doctor => {
    return activeSpecialty === 'all' || doctor.specialtyCategory === activeSpecialty;
  });

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
  }, [filteredDoctors]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => 
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '👨‍⚕️ أطباء' : '👨‍⚕️ Doctors'}
        </h2>

        {/* Specialty Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {specialtyFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeSpecialty === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveSpecialty(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {filteredDoctors.slice(0, 8).map((doctor) => {
              const isFavorite = favorites.includes(doctor.id);
              
              return (
                <div
                  key={doctor.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img 
                      src={doctor.image} 
                      alt="" 
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(doctor.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart 
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                      />
                    </button>

                    {/* Availability Badge */}
                    {doctor.isAvailable && (
                      <div className="absolute top-2 left-2">
                        <span className="px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-emerald-500/90 text-white flex items-center gap-1">
                          <div className="w-1.5 h-1.5 bg-white rounded-full animate-pulse" />
                          {isArabic ? 'متاح' : 'Available'}
                        </span>
                      </div>
                    )}

                    {/* Featured/New Badge */}
                    {(doctor.featured || doctor.new) && (
                      <div className="absolute bottom-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          doctor.featured ? 'bg-amber-500/90 text-white' : 'bg-rose-500/90 text-white'
                        }`}>
                          {doctor.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
                        </span>
                      </div>
                    )}

                    {/* Specialty Badge */}
                    <div className="absolute bottom-2 right-2">
                      <span className="px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-white/90 text-gray-700">
                        {isArabic ? doctor.specialty : doctor.specialtyEn}
                      </span>
                    </div>
                  </div>

                  {/* Doctor Info - Airbnb Style */}
                  <div>
                    {/* Rating */}
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{doctor.rating}</span>
                      <span className="text-xs text-gray-400">({doctor.reviews})</span>
                    </div>

                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? doctor.name : doctor.nameEn}
                    </h3>

                    {/* Next Available Time */}
                    <div className="flex items-center gap-1 text-xs text-gray-500">
                      <Calendar className="w-3 h-3 text-rose-500" />
                      <span>{isArabic ? 'القادم:' : 'Next:'} {doctor.nextAvailable}</span>
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
                  {filteredDoctors.slice(0, 4).map((d, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={d.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredDoctors.length} {isArabic ? 'طبيب' : 'doctors'}</span>
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
              {isArabic ? 'جميع الأطباء' : 'All Doctors'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredDoctors.length} {isArabic ? 'طبيب متاح' : 'doctors available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredDoctors.map((doctor) => {
                const isFavorite = favorites.includes(doctor.id);
                return (
                  <div key={doctor.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={doctor.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(doctor.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      {doctor.isAvailable && (
                        <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-emerald-500/90 text-white">
                          {isArabic ? 'متاح' : 'Available'}
                        </span>
                      )}
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-white/90 text-gray-700">
                        {isArabic ? doctor.specialty : doctor.specialtyEn}
                      </span>
                    </div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{doctor.rating}</span>
                      <span className="text-xs text-gray-400">({doctor.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? doctor.name : doctor.nameEn}</h3>
                    <div className="flex items-center gap-1 text-xs text-gray-500">
                      <Calendar className="w-3 h-3 text-rose-500" />
                      <span>{isArabic ? 'القادم:' : 'Next:'} {doctor.nextAvailable}</span>
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
